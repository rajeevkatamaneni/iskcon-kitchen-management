package org.iskcon.kms.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.observability.LogContext;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The background-job harness, exercised against a real scheduler and the Postgres job store.
 *
 * <p>The scheduler is off by default (only the worker runs it); this class turns it on. Timing is
 * inherent to the thing under test, so assertions await outcomes rather than assuming them.
 */
// Re-enables Quartz (the base test excludes it) and starts the scheduler, since this is the one
// test that exercises real job execution. The empty exclude overrides the base's.
@TestPropertySource(properties = {
		"spring.autoconfigure.exclude=",
		"spring.quartz.auto-startup=true"})
class BackgroundJobIT extends AbstractIntegrationTest {

	@Autowired
	private Scheduler scheduler;

	@Autowired
	private MeterRegistry meterRegistry;

	private final List<JobKey> scheduled = new ArrayList<>();

	@BeforeEach
	void resetProbes() {
		ProbeJob.RUNS.set(0);
		FailingJob.ATTEMPTS.set(0);
		TenantProbeJob.SEEN_TENANT.set(null);
		RequestIdProbeJob.SEEN_REQUEST_ID.set(null);
	}

	@AfterEach
	void cleanUp() throws SchedulerException {
		for (JobKey key : scheduled) {
			scheduler.deleteJob(key);
		}
		scheduled.clear();
	}

	@Test
	@DisplayName("a scheduled job fires")
	void scheduledJobFires() {
		schedule(ProbeJob.class, "probe", null);

		await().atMost(Duration.ofSeconds(15))
				.untilAsserted(() -> assertThat(ProbeJob.RUNS.get()).isPositive());
	}

	@Test
	@DisplayName("a job that keeps throwing is retried to its limit, then parked — not retried forever")
	void failingJobRetriesThenParks() {
		// maxAttempts 3 with a short backoff, so the three attempts complete quickly.
		schedule(FailingJob.class, "failing-test", null);

		await().atMost(Duration.ofSeconds(20))
				.untilAsserted(() -> assertThat(FailingJob.ATTEMPTS.get()).isEqualTo(3));

		// And it stops there — no fourth attempt in the couple of seconds after.
		await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3))
				.untilAsserted(() -> assertThat(FailingJob.ATTEMPTS.get()).isEqualTo(3));

		assertThat(meterRegistry.counter("kms.jobs.parked", "job", "failing-test").count())
				.as("the exhausted job is counted as parked, for the ops page to surface")
				.isEqualTo(1.0);
	}

	@Test
	@DisplayName("a tenant-scoped job runs inside its tenant's context")
	void tenantScopedJobSeesItsTenant() {
		UUID tenantId = UUID.randomUUID();
		schedule(TenantProbeJob.class, "tenant-probe", tenantId);

		await().atMost(Duration.ofSeconds(15))
				.untilAsserted(() -> assertThat(TenantProbeJob.SEEN_TENANT.get()).isEqualTo(tenantId));
	}

	@Test
	@DisplayName("a job runs under the request id that enqueued it")
	void jobCarriesRequestId() {
		JobKey key = new JobKey("reqid-" + UUID.randomUUID());
		JobDetail job = JobBuilder.newJob(RequestIdProbeJob.class).withIdentity(key).storeDurably()
				.usingJobData(KmsJob.REQUEST_ID_KEY, "req-abc").build();
		try {
			scheduler.scheduleJob(job, TriggerBuilder.newTrigger().forJob(job).startNow().build());
			scheduled.add(key);
		} catch (SchedulerException e) {
			throw new IllegalStateException("Failed to schedule test job", e);
		}

		await().atMost(Duration.ofSeconds(15))
				.untilAsserted(() -> assertThat(RequestIdProbeJob.SEEN_REQUEST_ID.get()).isEqualTo("req-abc"));
	}

	// ---------------------------------------------------------------------

	private void schedule(Class<? extends KmsJob> jobClass, String name, UUID tenantId) {
		JobKey key = new JobKey(name + "-" + UUID.randomUUID());
		JobDetail job = JobBuilder.newJob(jobClass).withIdentity(key).storeDurably().build();

		TriggerBuilder<Trigger> trigger = TriggerBuilder.newTrigger().forJob(job).startNow();
		if (tenantId != null) {
			trigger.usingJobData(KmsJob.TENANT_KEY, tenantId.toString());
		}

		try {
			scheduler.scheduleJob(job, trigger.build());
			scheduled.add(key);
		} catch (SchedulerException e) {
			throw new IllegalStateException("Failed to schedule test job", e);
		}
	}

	// ---------------------------------------------------------------------

	/** Records that it ran. */
	public static class ProbeJob extends KmsJob {
		static final AtomicInteger RUNS = new AtomicInteger();

		@Override
		protected String jobName() {
			return "probe-test";
		}

		@Override
		protected void run(JobExecutionContext context) {
			RUNS.incrementAndGet();
		}
	}

	/** Always throws; retried three times, with a short backoff, then parked. */
	public static class FailingJob extends KmsJob {
		static final AtomicInteger ATTEMPTS = new AtomicInteger();

		@Override
		protected String jobName() {
			return "failing-test";
		}

		@Override
		protected RetryPolicy retryPolicy() {
			return RetryPolicy.of(3, Duration.ofMillis(200));
		}

		@Override
		protected void run(JobExecutionContext context) {
			ATTEMPTS.incrementAndGet();
			throw new IllegalStateException("deliberate failure");
		}
	}

	/** Records the request id present on the logging context while running. */
	public static class RequestIdProbeJob extends KmsJob {
		static final AtomicReference<String> SEEN_REQUEST_ID = new AtomicReference<>();

		@Override
		protected String jobName() {
			return "reqid-probe-test";
		}

		@Override
		protected void run(JobExecutionContext context) {
			SEEN_REQUEST_ID.set(MDC.get(LogContext.REQUEST_ID));
		}
	}

	/** Records the tenant context it observed while running. */
	public static class TenantProbeJob extends KmsJob {
		static final AtomicReference<UUID> SEEN_TENANT = new AtomicReference<>();

		@Override
		protected String jobName() {
			return "tenant-probe-test";
		}

		@Override
		protected void run(JobExecutionContext context) {
			TenantContext.get().ifPresent(SEEN_TENANT::set);
		}
	}
}
