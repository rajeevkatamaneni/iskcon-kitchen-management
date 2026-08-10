package org.iskcon.kms.jobs;

import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The demo job this story ships: a nightly proof-of-life that the worker is running and the
 * schedule is firing. Does nothing else, and is trivially idempotent — running it twice looks
 * exactly like running it once. Later epics register the real jobs (reminders, PDFs, sends,
 * calendar precompute) the same way.
 *
 * <p>Not a Spring {@code @Component}: Quartz instantiates job classes itself, and Spring Boot's job
 * factory autowires the instance — which is how {@link KmsJob}'s metric registry is injected.
 */
public class HeartbeatJob extends KmsJob {

	private static final Logger log = LoggerFactory.getLogger(HeartbeatJob.class);

	@Override
	protected String jobName() {
		return "heartbeat";
	}

	@Override
	protected void run(JobExecutionContext context) {
		log.info("Heartbeat — the worker is alive and the schedule is firing.");
	}
}
