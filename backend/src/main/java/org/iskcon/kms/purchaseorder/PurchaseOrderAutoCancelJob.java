package org.iskcon.kms.purchaseorder;

import org.iskcon.kms.jobs.KmsJob;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Daily sweep cancelling drafts whose needed-by date has passed (T-137, D-24a). Idempotent by
 * construction: a swept order is no longer a draft, so a second run matches nothing.
 *
 * <p>Not a Spring {@code @Component} — Quartz instantiates job classes itself and Spring Boot's job
 * factory autowires the instance, which is how the runner below arrives.
 */
public class PurchaseOrderAutoCancelJob extends KmsJob {

	private static final Logger log = LoggerFactory.getLogger(PurchaseOrderAutoCancelJob.class);

	@Autowired
	private PurchaseOrderAutoCancelRunner runner;

	@Override
	protected String jobName() {
		return "purchase-order-auto-cancel";
	}

	@Override
	protected void run(JobExecutionContext context) {
		int cancelled = runner.sweep();
		if (cancelled > 0) {
			// Logged only when something happened, like the other sweeps. A nightly line saying
			// nothing was cancelled is the line an operator learns to skip past.
			log.info("Auto-cancelled {} draft purchase order(s) whose needed-by date had passed; "
					+ "what they asked for is back on the shopping list.", cancelled);
		}
	}
}
