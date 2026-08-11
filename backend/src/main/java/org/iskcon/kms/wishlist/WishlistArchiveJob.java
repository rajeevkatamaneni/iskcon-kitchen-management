package org.iskcon.kms.wishlist;

import org.iskcon.kms.jobs.KmsJob;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/** Daily auto-archive of fulfilled wish-list items past their visibility window (E7-S5). Idempotent. */
public class WishlistArchiveJob extends KmsJob {

	private static final Logger log = LoggerFactory.getLogger(WishlistArchiveJob.class);

	@Autowired
	private WishlistArchiveRunner runner;

	@Override
	protected String jobName() {
		return "wishlist-archive";
	}

	@Override
	protected void run(JobExecutionContext context) {
		int archived = runner.sweep();
		if (archived > 0) {
			log.info("Auto-archived {} fulfilled wish-list item(s).", archived);
		}
	}
}
