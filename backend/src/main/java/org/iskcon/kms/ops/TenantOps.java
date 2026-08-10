package org.iskcon.kms.ops;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A single temple's operational health, for the Super-Admin ops page. Platform-wide aggregates and
 * alerting live in Cloud Monitoring (fed by the Prometheus metrics); this is the in-app "how is
 * this temple doing today" drill-in.
 */
public record TenantOps(
		UUID tenantId,
		String tenantName,
		int sentToday,
		int failedToday,
		int suppressedToday,
		List<FailedSend> recentFailures,
		// Null until the calendar engine exists (E4); shown as "not available yet" on the page.
		Instant lastCalendarPrecompute) {

	/** A recent notification that failed on every channel — the thing an operator wants to see. */
	public record FailedSend(UUID id, String recipientLabel, String template, Instant failedAt) {
	}
}
