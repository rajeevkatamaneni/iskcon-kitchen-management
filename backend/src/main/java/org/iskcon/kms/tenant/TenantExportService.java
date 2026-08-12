package org.iskcon.kms.tenant;

import java.sql.ResultSetMetaData;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A complete copy of one temple's data, as a workbook (E1-S15).
 *
 * <p>This exists because deleting a temple is unconditional and irreversible: it erases the stock
 * ledger, the audit trail, vendor invoices and every donation record, including the ones behind 80G
 * certificates already issued to donors. The export is the safeguard that makes that survivable, so
 * {@link TenantDeletionService} refuses to delete a temple that has not been exported recently.
 *
 * <p>Tables are discovered the same way {@code delete_tenant_cascade} discovers them — "has a
 * {@code tenant_id} column" — so anything the purge destroys, this contains, without a maintained
 * list to fall out of step. The temple's own {@code tenants} row is added on top, since it has no
 * {@code tenant_id} of its own.
 *
 * <p>Reads run under the temple's own RLS context, set transaction-locally exactly as provisioning
 * does. A platform operator has no tenant of their own, so this is what lets them read the rows —
 * and, because the policy is what filters, it is also what guarantees no other temple's rows can
 * reach the file even if a query here were wrong.
 */
@Service
public class TenantExportService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public TenantExportService(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	/** The finished workbook and the name to offer it under. */
	public record Export(String filename, byte[] content) {
	}

	@Transactional
	public Export export(UUID tenantId, AuthenticatedUser actor) {
		Map<String, Object> tenant = loadTenant(tenantId);
		establishTenantContext(tenantId);

		Map<String, Object> counts = new LinkedHashMap<>();
		byte[] content;

		try (TenantExportWorkbook workbook = new TenantExportWorkbook()) {
			for (String table : TenantExportWorkbook.sheetOrder(exportableTables())) {
				counts.put(table, writeSheet(workbook, table, tenantId));
			}
			content = workbook.toBytes();
		}

		// The record of the export is also the fact the deletion guard reads (D6), so taking one and
		// being allowed to delete can never drift apart.
		auditService.recordPlatform(
				actor,
				AuditAction.TENANT_EXPORTED,
				AuditEntityType.TENANT,
				tenantId,
				null,
				counts,
				"Full data export taken by the platform operator.");

		return new Export(filename(String.valueOf(tenant.get("name"))), content);
	}

	/**
	 * {@code <Temple Name> - Data Export - <date>.xlsx}. Named after the temple so a file found in a
	 * folder a year later still says whose data it is (D11). Characters a filesystem or a browser
	 * would choke on are replaced; the name itself is otherwise left alone, accents and all.
	 *
	 * <p>Plain hyphens rather than dashes: any non-ASCII character forces the whole filename to be
	 * encoded in the download header, so a temple whose name is already ASCII keeps a header a person
	 * can read. A temple named in Devanagari is encoded regardless, and arrives correctly either way.
	 */
	static String filename(String templeName) {
		String cleaned = templeName.replaceAll("[\\\\/:*?\"<>|\\r\\n]", " ").replaceAll("\\s+", " ").trim();
		if (cleaned.isEmpty()) {
			cleaned = "Temple";
		}
		return cleaned + " - Data Export - " + LocalDate.now() + ".xlsx";
	}

	/** Streams one table into its own sheet; returns how many rows it wrote. */
	private int writeSheet(TenantExportWorkbook workbook, String table, UUID tenantId) {
		String sql = "tenants".equals(table)
				? "SELECT * FROM tenants WHERE id = ?"
				: "SELECT * FROM " + table + " WHERE tenant_id = ?";

		// The writer needs the column names before the first row, and the row handler cannot return
		// one, so the sheet is started lazily on the metadata of the first fetch.
		List<TenantExportWorkbook.SheetWriter> writer = new ArrayList<>(1);
		List<String> columns = new ArrayList<>();

		jdbc.query(sql, rs -> {
			if (writer.isEmpty()) {
				columns.addAll(columnNames(rs.getMetaData()));
				writer.add(workbook.startSheet(table, columns));
			}
			List<Object> values = new ArrayList<>(columns.size());
			for (int i = 1; i <= columns.size(); i++) {
				values.add(rs.getObject(i));
			}
			writer.get(0).addRow(values);
		}, tenantId);

		if (writer.isEmpty()) {
			// An empty table still gets its sheet: "we held nothing here" is information, and a
			// missing sheet reads as an export that forgot something.
			TenantExportWorkbook.SheetWriter empty = workbook.startSheet(table, columnNames(table));
			empty.finish();
			return 0;
		}

		writer.get(0).finish();
		return writer.get(0).rowCount();
	}

	private static List<String> columnNames(ResultSetMetaData meta) {
		try {
			List<String> names = new ArrayList<>(meta.getColumnCount());
			for (int i = 1; i <= meta.getColumnCount(); i++) {
				names.add(meta.getColumnLabel(i));
			}
			return names;
		} catch (java.sql.SQLException e) {
			throw new IllegalStateException("Could not read the column names of an exported table", e);
		}
	}

	/** Column names straight from the catalogue, for a table that returned no rows. */
	private List<String> columnNames(String table) {
		return jdbc.queryForList("""
				SELECT attname FROM pg_attribute
				WHERE attrelid = ?::regclass AND attnum > 0 AND NOT attisdropped
				ORDER BY attnum
				""", String.class, table);
	}

	/**
	 * Every table carrying a {@code tenant_id}, plus {@code tenants} itself. The same rule
	 * {@code delete_tenant_cascade} uses, so the export and the purge can never disagree about what
	 * belongs to a temple.
	 */
	private List<String> exportableTables() {
		List<String> tables = new ArrayList<>(jdbc.queryForList("""
				SELECT c.relname
				FROM pg_class c
				JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
				WHERE c.relkind = 'r' AND c.relnamespace = 'public'::regnamespace
				""", String.class));
		tables.add("tenants");
		return tables;
	}

	/**
	 * The temple as it stands — and the not-found guard. Reads the tenant registry, which is not
	 * tenant-scoped, so no context is needed yet.
	 */
	private Map<String, Object> loadTenant(UUID tenantId) {
		List<Map<String, Object>> rows =
				jdbc.queryForList("SELECT id, slug, name FROM tenants WHERE id = ?", tenantId);
		if (rows.isEmpty()) {
			throw new ApplicationException(ErrorCode.TENANT_NOT_FOUND, Map.of("tenantId", tenantId));
		}
		return rows.get(0);
	}

	/** Transaction-local, so it disappears at commit and cannot leak into another request. */
	private void establishTenantContext(UUID tenantId) {
		jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
	}
}
