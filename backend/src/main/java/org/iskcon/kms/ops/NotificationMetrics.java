package org.iskcon.kms.ops;

import java.time.LocalDate;
import java.util.List;

/**
 * Platform-wide notification-send figures for the Super-Admin Operations page: today's totals and a
 * seven-day pulse, across every temple. Each day is split into twelve two-hour buckets, so the
 * operator reads not just how many messages went out but <em>when</em> — a cluster of failures in
 * the small hours points at the nightly job, a morning spike at shift reminders.
 *
 * <p>This is a deliberate, narrow exception to the "no cross-tenant firehose" rule that governs the
 * audit drill-in (see {@code OpsService}). The distinction is what crosses the boundary: the audit
 * feed carries a temple's <em>business records</em> (donation amounts, payment detail), so exposing
 * it platform-wide would be a side door around RBAC. These are <em>aggregate counts of send
 * outcomes</em> — no recipient, no template, no temple attribution — which carry none of a temple's
 * business data. They are exactly the operational vital sign a platform operator needs and could
 * otherwise only get from Cloud Monitoring. The counts are still assembled from properly
 * tenant-scoped reads (one per temple, under RLS), never a BYPASSRLS query.
 */
public record NotificationMetrics(int sentToday, int failedToday, List<DayPulse> days) {

	/**
	 * One day's send outcomes across all temples, bucketed into twelve two-hour slots. Index 0 is
	 * 00:00–02:00 and index 11 is 22:00–24:00, in the platform display timezone. Both lists always
	 * have twelve entries. The list of days is oldest first; the last entry is today.
	 */
	public record DayPulse(LocalDate date, List<Integer> sent, List<Integer> failed) {
	}
}
