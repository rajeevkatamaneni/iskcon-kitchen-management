package org.iskcon.kms.tenant;

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
 * Permanently removes a temple and everything belonging to it.
 *
 * <p>The counterpart to {@link TenantProvisioningService}. A temple is deliberately hard to delete —
 * every tenant-owned table references it with {@code ON DELETE RESTRICT}, and nine are append-only —
 * so the erasure is done by the audited {@code delete_tenant_cascade} database function (V44), the
 * single path allowed to cross both guards, and only for a whole-tenant purge.
 *
 * <p>Order matters. The deletion is recorded on the <b>platform</b> audit log <em>before</em> the
 * purge runs, because the temple's own audit trail is erased along with it — the platform record,
 * which carries no {@code tenant_id}, is the only durable proof of what happened and who did it.
 */
@Service
public class TenantDeletionService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public TenantDeletionService(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	@Transactional
	public void delete(UUID tenantId, AuthenticatedUser actor) {
		Map<String, Object> snapshot = loadSnapshot(tenantId);

		// Record on the platform log first — it outlives the temple and is not part of the purge.
		auditService.recordPlatform(
				actor,
				AuditAction.TENANT_DELETED,
				AuditEntityType.TENANT,
				tenantId,
				snapshot,
				null,
				"Temple and all its data permanently deleted by the platform operator.");

		// Erase everything for the temple, then the temple row. Runs the void function as a query so
		// PostgreSQL does not reject it the way it rejects a SELECT sent as an update.
		jdbc.query("SELECT delete_tenant_cascade(?)", (java.sql.ResultSet rs) -> {}, tenantId);
	}

	/**
	 * The temple as it was, for the audit before-state — and the not-found guard. Reads the tenant
	 * registry, which is not tenant-scoped, so no context is needed.
	 */
	private Map<String, Object> loadSnapshot(UUID tenantId) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT id, slug, name, timezone, currency, is_80g_approved, status, created_at
				FROM tenants WHERE id = ?
				""", tenantId);

		if (rows.isEmpty()) {
			throw new ApplicationException(ErrorCode.TENANT_NOT_FOUND, Map.of("tenantId", tenantId));
		}

		// A stable, legible order for the JSONB snapshot; stringify the id so it reads cleanly.
		Map<String, Object> row = rows.get(0);
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("tenantId", String.valueOf(row.get("id")));
		snapshot.put("slug", row.get("slug"));
		snapshot.put("name", row.get("name"));
		snapshot.put("timezone", row.get("timezone"));
		snapshot.put("currency", row.get("currency"));
		snapshot.put("is80gApproved", row.get("is_80g_approved"));
		return snapshot;
	}
}
