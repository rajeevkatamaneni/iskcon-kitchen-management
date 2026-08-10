package org.iskcon.kms.inventory;

import org.iskcon.kms.jobs.KmsJob;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The nightly job that sends every temple its low-stock digest (E3-S3). Platform-level — it sets no
 * tenant of its own; the sweep it delegates to establishes each temple's context in turn.
 *
 * <p>Idempotent by way of the once-per-day claim in {@link LowStockAlertService}: re-running the job
 * (a retry, or a manual re-fire) re-sends nothing that already went out today.
 */
public class LowStockDigestJob extends KmsJob {

	private static final Logger log = LoggerFactory.getLogger(LowStockDigestJob.class);

	@Autowired
	private LowStockDigestRunner runner;

	@Override
	protected String jobName() {
		return "low-stock-digest";
	}

	@Override
	protected void run(JobExecutionContext context) {
		int sent = runner.sweep();
		log.info("Low-stock digest sweep complete — {} temple(s) notified.", sent);
	}
}
