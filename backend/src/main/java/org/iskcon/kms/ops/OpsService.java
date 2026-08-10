package org.iskcon.kms.ops;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The data behind the Super-Admin ops page. The tenant list comes from the (non-RLS) tenant
 * registry; a temple's operational figures come from a **drill-in** — the operator's context is set
 * to that one temple and its own notifications are read under ordinary RLS. There is deliberately no
 * cross-tenant read: platform-wide totals are Cloud Monitoring's job (the Prometheus metrics), and a
 * cross-tenant firehose here would be the same side-door around RBAC the audit log avoids.
 */
@Service
public class OpsService {

	private final JdbcTemplate jdbc;

	public OpsService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** The temples an operator can drill into. The tenant registry is not tenant-scoped. */
	public List<Map<String, Object>> tenants() {
		return jdbc.queryForList("SELECT id, name, slug, status FROM tenants ORDER BY name");
	}

	/** One temple's operational health, read within that temple's own context. */
	public TenantOps tenantOperations(UUID tenantId) {
		String name = tenantName(tenantId);

		TenantContext.set(tenantId);
		try {
			Map<String, Integer> byStatus = todayCountsByStatus();
			int sent = byStatus.getOrDefault("SENT", 0) + byStatus.getOrDefault("DELIVERED", 0);
			int failed = byStatus.getOrDefault("FAILED", 0);
			int suppressed = byStatus.getOrDefault("SUPPRESSED", 0);

			return new TenantOps(
					tenantId, name, sent, failed, suppressed, recentFailures(), lastCalendarPrecompute());
		} finally {
			TenantContext.clear();
		}
	}

	/** When this temple's Vaishnava calendar was last precomputed (E4-S1), or null if never. */
	private java.time.Instant lastCalendarPrecompute() {
		return jdbc.query("SELECT last_run_at FROM calendar_precompute_state",
				(rs, rowNum) -> rs.getObject("last_run_at", OffsetDateTime.class).toInstant())
				.stream().findFirst().orElse(null);
	}

	private String tenantName(UUID tenantId) {
		List<String> names = jdbc.query(
				"SELECT name FROM tenants WHERE id = ?", (rs, rowNum) -> rs.getString("name"), tenantId);
		if (names.isEmpty()) {
			throw new ApplicationException(ErrorCode.TENANT_NOT_FOUND, Map.of("tenantId", tenantId));
		}
		return names.get(0);
	}

	private Map<String, Integer> todayCountsByStatus() {
		Map<String, Integer> counts = new HashMap<>();
		jdbc.query(
				"SELECT status, count(*) AS c FROM notifications "
						+ "WHERE created_at >= date_trunc('day', now()) GROUP BY status",
				(java.sql.ResultSet rs) -> {
					counts.put(rs.getString("status"), rs.getInt("c"));
				});
		return counts;
	}

	private List<TenantOps.FailedSend> recentFailures() {
		return jdbc.query("""
				SELECT id, recipient_label, template, updated_at
				FROM notifications WHERE status = 'FAILED'
				ORDER BY updated_at DESC LIMIT 10
				""", (rs, rowNum) -> new TenantOps.FailedSend(
						rs.getObject("id", UUID.class),
						rs.getString("recipient_label"),
						rs.getString("template"),
						rs.getObject("updated_at", OffsetDateTime.class).toInstant()));
	}
}
