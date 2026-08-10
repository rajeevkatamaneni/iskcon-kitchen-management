package org.iskcon.kms.jobs;

import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the jobs the application schedules by default. Spring Boot picks up {@link JobDetail}
 * and {@link Trigger} beans and hands them to the scheduler, so a new scheduled job is a pair of
 * beans here — the same shape later epics will follow.
 */
@Configuration
public class JobSchedulingConfiguration {

	@Bean
	public JobDetail heartbeatJobDetail() {
		return JobBuilder.newJob(HeartbeatJob.class)
				.withIdentity("heartbeat")
				.withDescription("Nightly proof-of-life that the worker and schedule are running.")
				// Durable so it survives with no live trigger, and can be re-triggered by a retry.
				.storeDurably()
				// Re-run if the worker died mid-execution — safe because the job is idempotent.
				.requestRecovery()
				.build();
	}

	@Bean
	public Trigger heartbeatTrigger(JobDetail heartbeatJobDetail) {
		return TriggerBuilder.newTrigger()
				.forJob(heartbeatJobDetail)
				.withIdentity("heartbeat-nightly")
				.withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(2, 0))
				.build();
	}
}
