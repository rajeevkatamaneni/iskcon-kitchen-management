package org.iskcon.kms.calendar;

import org.iskcon.kms.jobs.KmsJob;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Precomputes the Vaishnava calendar (E4-S1). Two modes, one class:
 *
 * <ul>
 *   <li><strong>Nightly</strong> — no tenant in its job data; it sweeps every active temple.
 *   <li><strong>One-off</strong> — carries a {@code TENANT_KEY} (queued when a temple is provisioned,
 *       so its calendar is ready immediately); KmsJob has already established that tenant's context,
 *       so it precomputes just that one.
 * </ul>
 *
 * <p>Idempotent via the upsert in {@link CalendarService} — a re-run refreshes days rather than
 * duplicating them, satisfying KmsJob's at-least-once contract.
 */
public class CalendarPrecomputeJob extends KmsJob {

	private static final Logger log = LoggerFactory.getLogger(CalendarPrecomputeJob.class);

	@Autowired
	private CalendarService calendarService;

	@Autowired
	private CalendarPrecomputeRunner runner;

	@Override
	protected String jobName() {
		return "calendar-precompute";
	}

	@Override
	protected void run(JobExecutionContext context) {
		String tenantId = context.getMergedJobDataMap().getString(KmsJob.TENANT_KEY);
		if (tenantId != null) {
			int days = calendarService.precomputeForCurrentTenant();
			log.info("Calendar precompute for tenant {} wrote {} days.", tenantId, days);
		} else {
			int temples = runner.sweep();
			log.info("Calendar precompute sweep refreshed {} temple(s).", temples);
		}
	}
}
