package org.iskcon.kms.jobs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.iskcon.kms.observability.LogContext;
import org.iskcon.kms.tenancy.TenantContext;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Base class for every background job. A concrete job implements {@link #run} and {@link #jobName};
 * everything a job must do the same way — tenant scoping, retry with backoff, and making success
 * and failure visible — lives here so no individual job has to remember it.
 *
 * <p><strong>Every job must be idempotent.</strong> This is not advice, it is a requirement: a job
 * can run more than once for the same trigger. Retries re-run it; {@code requestRecovery} re-runs a
 * job that was executing when the worker died; and at-least-once delivery is the honest guarantee a
 * persistent queue gives. So running a job twice must be indistinguishable from running it once —
 * check whether the work is already done, use natural keys, make writes upserts. A job that sends a
 * message twice or double-charges a card is a bug in the job, not in the framework.
 *
 * <p><strong>Tenant scoping.</strong> A job that touches a temple's data puts that temple's id in
 * its job data under {@link #TENANT_KEY}; this class establishes it as the tenant context for the
 * run and clears it after, so the job sees exactly what a request for that tenant would — RLS and
 * all. A platform-level job (the heartbeat) sets no tenant and runs outside any.
 *
 * <p><strong>A temple that no longer exists.</strong> Before running tenant-scoped work this class
 * checks the temple is still there, and if it is not it returns without running the job and without
 * recording a failure — see {@link #tenantStillExists}. Deleting a temple takes its scheduled work
 * with it (V86), so this only ever catches a job already in flight when that happened.
 */
public abstract class KmsJob implements Job {

	private static final Logger log = LoggerFactory.getLogger(KmsJob.class);

	/** Job-data key holding the current attempt number (1-based). Managed by this class. */
	static final String ATTEMPT_KEY = "kms.attempt";

	/** Job-data key holding the tenant id (a UUID string) a tenant-scoped job runs within. */
	public static final String TENANT_KEY = "kms.tenantId";

	/**
	 * Job-data key holding the request id that enqueued this job, so the worker's log lines share
	 * an id with the request that triggered the work. A scheduled job with no originating request
	 * gets a fresh id.
	 */
	public static final String REQUEST_ID_KEY = "kms.requestId";

	@Autowired
	private MeterRegistry meterRegistry;

	@Autowired
	private JdbcTemplate jdbc;

	/** The work. May run more than once — see the idempotency requirement on the class. */
	protected abstract void run(JobExecutionContext context) throws Exception;

	/** A short, stable name used in logs and metric tags. */
	protected abstract String jobName();

	/** How the job is retried. Defaults to no retry. */
	protected RetryPolicy retryPolicy() {
		return RetryPolicy.NONE;
	}

	@Override
	public final void execute(JobExecutionContext context) throws JobExecutionException {
		JobDataMap data = context.getMergedJobDataMap();
		int attempt = data.containsKey(ATTEMPT_KEY) ? data.getInt(ATTEMPT_KEY) : 1;
		String tenantId = data.getString(TENANT_KEY);

		establishLogContext(data, tenantId);
		boolean tenantEstablished = establishTenant(tenantId);
		try {
			if (tenantId != null && !tenantStillExists(tenantId)) {
				counter("kms.jobs.abandoned").increment();
				log.info("Job {} abandoned: temple {} has been deleted, so its queued work has "
						+ "nothing to act on.", jobName(), tenantId);
				return;
			}

			run(context);
			counter("kms.jobs.completed").increment();
			log.info("Job {} completed on attempt {}{}", jobName(), attempt, tenantSuffix(tenantId));

		} catch (Exception e) {
			counter("kms.jobs.failed").increment();
			RetryPolicy policy = retryPolicy();

			if (attempt < policy.maxAttempts()) {
				log.warn("Job {} failed on attempt {} of {}{}; will retry after backoff",
						jobName(), attempt, policy.maxAttempts(), tenantSuffix(tenantId), e);
				scheduleRetry(context, attempt + 1, tenantId, policy.backoffAfterAttempt(attempt));
			} else {
				counter("kms.jobs.parked").increment();
				log.error("Job {} failed on final attempt {} of {}{}; parked as failed",
						jobName(), attempt, policy.maxAttempts(), tenantSuffix(tenantId), e);
			}
			// Deliberately not rethrown: any retry is a fresh trigger we scheduled ourselves.
			// Rethrowing would set Quartz's own misfire/refire going on top of our policy.

		} finally {
			if (tenantEstablished) {
				TenantContext.clear();
			}
			MDC.remove(LogContext.REQUEST_ID);
			MDC.remove(LogContext.TENANT_ID);
		}
	}

	/** Carries the enqueuing request's id (or a fresh one) and the tenant onto the worker's MDC. */
	private void establishLogContext(JobDataMap data, String tenantId) {
		String requestId = data.getString(REQUEST_ID_KEY);
		MDC.put(LogContext.REQUEST_ID,
				requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId);
		if (tenantId != null) {
			MDC.put(LogContext.TENANT_ID, tenantId);
		}
	}

	private void scheduleRetry(JobExecutionContext context, int nextAttempt, String tenantId, java.time.Duration backoff) {
		JobDataMap retryData = new JobDataMap();
		retryData.put(ATTEMPT_KEY, nextAttempt);
		if (tenantId != null) {
			retryData.put(TENANT_KEY, tenantId);
		}

		Trigger retry = TriggerBuilder.newTrigger()
				.forJob(context.getJobDetail().getKey())
				.startAt(Date.from(Instant.now().plus(backoff)))
				.usingJobData(retryData)
				.withSchedule(SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
				.build();

		try {
			context.getScheduler().scheduleJob(retry);
		} catch (Exception schedulingError) {
			// If we cannot even schedule the retry, the attempt is lost — treat it as parked so it
			// is not silently forgotten.
			counter("kms.jobs.parked").increment();
			log.error("Job {} could not schedule its retry; parked", jobName(), schedulingError);
		}
	}

	/**
	 * Whether the temple this job is for still exists.
	 *
	 * <p>Deleting a temple removes its scheduled work in the same transaction (V86), but a worker
	 * elsewhere may already have picked a trigger up when that transaction commits — the two run in
	 * different processes and there is no lock either could take that the other would honour. So the
	 * job asks. Making the deletion wait instead was the alternative and was rejected: it would
	 * block an operator's request on however long somebody else's job takes, to buy nothing this
	 * check does not already give.
	 *
	 * <p>Answering "no" is not a failure. The work was queued for a temple that has since been
	 * erased; there is nothing to do and nobody to tell, so the job returns quietly rather than
	 * throwing — which is what used to fill the log with KMS-4401 and park a fresh failure every
	 * time. It is counted, under {@code kms.jobs.abandoned}, because "nothing happened" and "we
	 * decided not to" must not look the same on the ops page.
	 *
	 * <p>The tenant registry is not RLS-protected, so this reads the same answer whatever context
	 * the job is running in.
	 */
	private boolean tenantStillExists(String tenantId) {
		Integer found = jdbc.queryForObject(
				"SELECT count(*) FROM tenants WHERE id = ?::uuid", Integer.class, tenantId);
		return found != null && found > 0;
	}

	private boolean establishTenant(String tenantId) {
		if (tenantId == null) {
			return false;
		}
		TenantContext.set(UUID.fromString(tenantId));
		return true;
	}

	private Counter counter(String name) {
		return meterRegistry.counter(name, "job", jobName());
	}

	private String tenantSuffix(String tenantId) {
		return tenantId == null ? "" : " (tenant " + tenantId + ")";
	}
}
