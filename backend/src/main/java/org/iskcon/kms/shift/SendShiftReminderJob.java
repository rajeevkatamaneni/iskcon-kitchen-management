package org.iskcon.kms.shift;

import java.util.UUID;
import org.iskcon.kms.jobs.KmsJob;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Background job that sends one scheduled shift reminder (E6-S6). A thin Quartz wrapper over
 * {@link ShiftReminderService}, which does the idempotent send; KmsJob supplies the tenant context.
 */
public class SendShiftReminderJob extends KmsJob {

	/** Job-data key holding the signup id (a UUID string) to remind. */
	public static final String SIGNUP_ID_KEY = "kms.shift.signupId";

	/** Job-data key holding the reminder offset in minutes. */
	public static final String OFFSET_KEY = "kms.shift.offsetMinutes";

	@Autowired
	private ShiftReminderService shiftReminderService;

	@Override
	protected void run(JobExecutionContext context) {
		String signupId = context.getMergedJobDataMap().getString(SIGNUP_ID_KEY);
		int offset = context.getMergedJobDataMap().getInt(OFFSET_KEY);
		shiftReminderService.sendReminder(UUID.fromString(signupId), offset);
	}

	@Override
	protected String jobName() {
		return "send-shift-reminder";
	}
}
