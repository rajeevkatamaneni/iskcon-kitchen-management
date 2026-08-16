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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
	private final org.iskcon.kms.tenancy.TenantSecretStore secrets;

	public TenantDeletionService(JdbcTemplate jdbc, AuditService auditService,
			org.iskcon.kms.tenancy.TenantSecretStore secrets) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.secrets = secrets;
	}

	/**
	 * How recent an export has to be to count. An export from last month is not a safeguard — the
	 * temple has traded since — and a window is something a person can be told and a test can assert.
	 */
	private static final int EXPORT_VALID_HOURS = 24;

	@Transactional
	public void delete(UUID tenantId, AuthenticatedUser actor) {
		Map<String, Object> snapshot = loadSnapshot(tenantId);
		requireRecentExport(tenantId);

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

		// The temple's secrets are not in this database and so cannot be erased by this transaction.
		// Deliberately after the commit, never before: a secret left behind by a failed erase can be
		// swept up later — its name is derivable from the tenant id — whereas credentials destroyed
		// ahead of a purge that then rolled back would leave a living temple unable to take a
		// donation, with nothing to say why.
		afterCommit(() -> secrets.deleteAll(tenantId));
	}

	private static void afterCommit(Runnable work) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			work.run();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				work.run();
			}
		});
	}

	/**
	 * Refuses the deletion unless a copy of the temple was taken recently (E1-S15, D6).
	 *
	 * <p>Deletion here is unconditional by decision — a temple with donations and filed 80G records
	 * can be erased like any other — so the export is the whole of the safety net, and a safety net
	 * the screen merely suggests is not one. The check reads the same {@code TENANT_EXPORTED} event
	 * the export writes, so what the log says happened and what the guard allows cannot diverge.
	 */
	private void requireRecentExport(UUID tenantId) {
		Integer recent = jdbc.queryForObject("""
				SELECT count(*) FROM platform_audit_events
				WHERE action = 'TENANT_EXPORTED'
				  AND entity_id = ?
				  AND created_at > now() - make_interval(hours => ?)
				""", Integer.class, tenantId, EXPORT_VALID_HOURS);

		if (recent == null || recent == 0) {
			throw new ApplicationException(
					ErrorCode.EXPORT_REQUIRED_BEFORE_DELETE,
					Map.of("tenantId", tenantId, "withinHours", EXPORT_VALID_HOURS));
		}
	}

	/**
	 * The temple as it was, for the audit before-state — and the not-found guard. Reads the tenant
	 * registry, which is not tenant-scoped, so no context is needed.
	 */
	private Map<String, Object> loadSnapshot(UUID tenantId) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT id, slug, name, timezone, currency, is_80g_approved, created_at
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
