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
