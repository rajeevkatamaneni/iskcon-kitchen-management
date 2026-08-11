package org.iskcon.kms.ops;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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

	/** Two-hour buckets per day: index 0 is 00:00–02:00, index 11 is 22:00–24:00. */
	private static final int SLOTS = 12;

	/**
	 * Platform-wide send figures for the Operations page: today's sent/failed totals and a seven-day
	 * pulse — each day split into twelve two-hour buckets — summed across every temple. See
	 * {@link NotificationMetrics} for why this cross-tenant aggregate is a legitimate exception to the
	 * per-tenant drill-in rule.
	 *
	 * <p>The app role holds no BYPASSRLS, so there is no single query that spans tenants. Instead we
	 * read each temple under its own RLS context and sum the counts — a total assembled from honest,
	 * scoped reads rather than a side door around the policy.
	 *
	 * <p>Day and hour boundaries are taken in <b>Asia/Kolkata</b>, the platform display timezone: the
	 * app is India-first (every temple is in IST), and an operator reading "the morning reminder spike"
	 * means their local morning, not UTC. Taking "today" from the same clock keeps the seven-day axis
	 * complete even on days when no temple sent anything.
	 */
	public NotificationMetrics notificationMetrics() {
		LocalDate today = jdbc.queryForObject(
				"SELECT (now() AT TIME ZONE 'Asia/Kolkata')::date", LocalDate.class);
		LocalDate from = today.minusDays(6);

		// date -> { sent[12], failed[12] }, accumulated across all temples.
		Map<LocalDate, int[][]> byDay = new HashMap<>();

		for (Map<String, Object> tenant : tenants()) {
			UUID tenantId = (UUID) tenant.get("id");
			TenantContext.set(tenantId);
			try {
				jdbc.query("""
						SELECT (date_trunc('day', created_at AT TIME ZONE 'Asia/Kolkata'))::date AS day,
						       (extract(hour from created_at AT TIME ZONE 'Asia/Kolkata')::int / 2) AS slot,
						       status, count(*) AS c
						FROM notifications
						WHERE created_at >= (date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata')
						                     - interval '6 days') AT TIME ZONE 'Asia/Kolkata'
						GROUP BY day, slot, status
						""", (java.sql.ResultSet rs) -> {
					LocalDate day = rs.getObject("day", LocalDate.class);
					int slot = rs.getInt("slot");
					String status = rs.getString("status");
					int count = rs.getInt("c");
					if (slot < 0 || slot >= SLOTS) {
						return; // defensive: a 24:00 leap-second edge would land outside the grid
					}
					int[][] buckets = byDay.computeIfAbsent(day, k -> new int[2][SLOTS]);
					if ("SENT".equals(status) || "DELIVERED".equals(status)) {
						buckets[0][slot] += count;
					} else if ("FAILED".equals(status)) {
						buckets[1][slot] += count;
					}
				});
			} finally {
				TenantContext.clear();
			}
		}

		List<NotificationMetrics.DayPulse> days = new ArrayList<>();
		for (LocalDate day = from; !day.isAfter(today); day = day.plusDays(1)) {
			int[][] buckets = byDay.getOrDefault(day, new int[2][SLOTS]);
			days.add(new NotificationMetrics.DayPulse(day, boxed(buckets[0]), boxed(buckets[1])));
		}
		int[][] todayBuckets = byDay.getOrDefault(today, new int[2][SLOTS]);
		return new NotificationMetrics(sum(todayBuckets[0]), sum(todayBuckets[1]), days);
	}

	private static List<Integer> boxed(int[] values) {
		List<Integer> out = new ArrayList<>(values.length);
		for (int v : values) {
			out.add(v);
		}
		return out;
	}

	private static int sum(int[] values) {
		int total = 0;
		for (int v : values) {
			total += v;
		}
		return total;
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
