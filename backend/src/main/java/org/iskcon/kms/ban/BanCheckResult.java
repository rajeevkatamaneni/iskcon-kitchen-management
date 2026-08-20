package org.iskcon.kms.ban;

import java.util.List;
import java.util.UUID;

/**
 * What the check at hire found (B9).
 *
 * <p>{@code checkId} exists whether anything was found or not. It is the id the query was recorded
 * under on the platform audit log, and it is what the admin's answer is later filed against — so a
 * check that found nothing is still a check somebody ran, with a name attached, which is the whole
 * reason the log is kept. It is not a token and grants nothing.
 *
 * @param checkId  the id this query was recorded under
 * @param findings the records that might be about this person; empty far more often than not
 */
public record BanCheckResult(UUID checkId, List<BanFinding> findings) {

	public boolean foundSomething() {
		return !findings.isEmpty();
	}
}
