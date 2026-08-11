package org.iskcon.kms.jobs;

import java.util.TimeZone;
import org.iskcon.kms.calendar.CalendarPrecomputeJob;
import org.iskcon.kms.donation.ExpirePendingDonationsJob;
import org.iskcon.kms.inventory.LowStockDigestJob;
import org.iskcon.kms.order.OrderListRegenerateJob;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
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

	@Bean
	public JobDetail lowStockDigestJobDetail() {
		return JobBuilder.newJob(LowStockDigestJob.class)
				.withIdentity("low-stock-digest")
				.withDescription("Daily digest of consumables below their reorder level, per temple (E3-S3).")
				.storeDurably()
				.requestRecovery()
				.build();
	}

	@Bean
	public JobDetail calendarPrecomputeJobDetail() {
		return JobBuilder.newJob(CalendarPrecomputeJob.class)
				.withIdentity("calendar-precompute")
				.withDescription("Nightly precompute of the Vaishnava calendar, 18 months ahead per temple (E4-S1).")
				.storeDurably()
				.requestRecovery()
				.build();
	}

	@Bean
	public Trigger calendarPrecomputeTrigger(JobDetail calendarPrecomputeJobDetail) {
		// Small hours IST, after the heartbeat and before the low-stock digest reads the calendar.
		return TriggerBuilder.newTrigger()
				.forJob(calendarPrecomputeJobDetail)
				.withIdentity("calendar-precompute-nightly")
				.withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(3, 0)
						.inTimeZone(TimeZone.getTimeZone("Asia/Kolkata")))
				.build();
	}

	@Bean
	public JobDetail orderListRegenerateJobDetail() {
		return JobBuilder.newJob(OrderListRegenerateJob.class)
				.withIdentity("order-list-regenerate")
				.withDescription("Nightly regeneration of the suggested order list per temple (E5-S2).")
				.storeDurably()
				.requestRecovery()
				.build();
	}

	@Bean
	public Trigger orderListRegenerateTrigger(JobDetail orderListRegenerateJobDetail) {
		// After the calendar precompute (03:00) so shortfalls reflect the fresh calendar.
		return TriggerBuilder.newTrigger()
				.forJob(orderListRegenerateJobDetail)
				.withIdentity("order-list-regenerate-nightly")
				.withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(4, 30)
						.inTimeZone(TimeZone.getTimeZone("Asia/Kolkata")))
				.build();
	}

	@Bean
	public Trigger lowStockDigestTrigger(JobDetail lowStockDigestJobDetail) {
		// Early morning IST, so staff see what's low as they start the day, wherever the worker runs.
		return TriggerBuilder.newTrigger()
				.forJob(lowStockDigestJobDetail)
				.withIdentity("low-stock-digest-daily")
				.withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(6, 0)
						.inTimeZone(TimeZone.getTimeZone("Asia/Kolkata")))
				.build();
	}

	@Bean
	public JobDetail expirePendingDonationsJobDetail() {
		return JobBuilder.newJob(ExpirePendingDonationsJob.class)
				.withIdentity("expire-pending-donations")
				.withDescription("Hourly sweep of abandoned PENDING online donations to EXPIRED (E7-S2).")
				.storeDurably()
				.requestRecovery()
				.build();
	}

	@Bean
	public Trigger expirePendingDonationsTrigger(JobDetail expirePendingDonationsJobDetail) {
		// Hourly — a checkout window is short, so an abandoned intent shouldn't linger long.
		return TriggerBuilder.newTrigger()
				.forJob(expirePendingDonationsJobDetail)
				.withIdentity("expire-pending-donations-hourly")
				.withSchedule(SimpleScheduleBuilder.repeatHourlyForever())
				.build();
	}
}
