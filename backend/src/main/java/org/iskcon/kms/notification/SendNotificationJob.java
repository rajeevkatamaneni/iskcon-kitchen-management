package org.iskcon.kms.notification;

import java.time.Duration;
import java.util.UUID;
import org.iskcon.kms.jobs.KmsJob;
import org.iskcon.kms.jobs.RetryPolicy;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Runs one queued notification off the request path. The job carries only the notification's id and
 * its tenant; {@link KmsJob} establishes the tenant context, and {@link NotificationDispatcher} does
 * the sending — which is idempotent, so this job's retries are safe.
 */
public class SendNotificationJob extends KmsJob {

	@Autowired
	private NotificationDispatcher dispatcher;

	@Override
	protected String jobName() {
		return "send-notification";
	}

	@Override
	protected RetryPolicy retryPolicy() {
		// A send is worth a few tries on a transient failure before it is given up on.
		return RetryPolicy.of(3, Duration.ofSeconds(5));
	}

	@Override
	protected void run(JobExecutionContext context) {
		UUID notificationId = UUID.fromString(
				context.getMergedJobDataMap().getString(NotificationService.NOTIFICATION_ID_KEY));
		dispatcher.dispatch(notificationId);
	}
}
