package org.iskcon.kms.calendar;

import java.util.UUID;
import org.iskcon.kms.jobs.KmsJob;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Queues a one-off calendar precompute for a single tenant — used when a temple is provisioned
 * (E1-S6), so its calendar is ready without waiting for the nightly sweep.
 *
 * <p>Best-effort by design: the scheduler lives where the worker runs, so if none is available (a
 * context without the worker) this quietly does nothing and the nightly sweep will pick the tenant
 * up. Queuing a calendar must never be able to fail provisioning.
 */
@Component
public class CalendarPrecomputeScheduler {

	private static final Logger log = LoggerFactory.getLogger(CalendarPrecomputeScheduler.class);

	private final ObjectProvider<Scheduler> scheduler;

	public CalendarPrecomputeScheduler(ObjectProvider<Scheduler> scheduler) {
		this.scheduler = scheduler;
	}

	public void enqueueForTenant(UUID tenantId) {
		Scheduler quartz = scheduler.getIfAvailable();
		if (quartz == null) {
			log.debug("No scheduler; tenant {} calendar will be built by the nightly sweep.", tenantId);
			return;
		}

		JobDetail job = JobBuilder.newJob(CalendarPrecomputeJob.class)
				.withIdentity("calendar-precompute-" + tenantId)
				.usingJobData(KmsJob.TENANT_KEY, tenantId.toString())
				.requestRecovery()
				.build();
		Trigger trigger = TriggerBuilder.newTrigger().forJob(job).startNow().build();

		try {
			quartz.scheduleJob(job, trigger);
		} catch (SchedulerException e) {
			log.warn("Could not queue calendar precompute for tenant {}: {}", tenantId, e.toString());
		}
	}
}
