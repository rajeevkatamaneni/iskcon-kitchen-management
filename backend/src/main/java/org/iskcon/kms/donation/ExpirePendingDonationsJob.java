package org.iskcon.kms.donation;

import org.iskcon.kms.jobs.KmsJob;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/** Hourly sweep of abandoned PENDING online donations to EXPIRED (E7-S2). Idempotent by construction. */
public class ExpirePendingDonationsJob extends KmsJob {

	private static final Logger log = LoggerFactory.getLogger(ExpirePendingDonationsJob.class);

	@Autowired
	private ExpirePendingDonationsRunner runner;

	@Override
	protected String jobName() {
		return "expire-pending-donations";
	}

	@Override
	protected void run(JobExecutionContext context) {
		int expired = runner.sweep();
		if (expired > 0) {
			log.info("Expired {} abandoned pending donation(s).", expired);
		}
	}
}
