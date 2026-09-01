package org.iskcon.kms.shoppinglist;

import org.iskcon.kms.jobs.KmsJob;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Nightly regeneration of the suggested shopping list for every temple (E5-S2). Idempotent — the
 * regeneration itself is edit-preserving and upsert-based, so re-running refreshes rather than
 * duplicates.
 */
public class ShoppingListRegenerateJob extends KmsJob {

	private static final Logger log = LoggerFactory.getLogger(ShoppingListRegenerateJob.class);

	@Autowired
	private ShoppingListRegenerateRunner runner;

	@Override
	protected String jobName() {
		return "shopping-list-regenerate";
	}

	@Override
	protected void run(JobExecutionContext context) {
		int temples = runner.sweep();
		log.info("Shopping-list regeneration refreshed {} temple(s).", temples);
	}
}
