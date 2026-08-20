package org.iskcon.kms.ban;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A ban record as its own temple sees it (B9).
 *
 * <p>Served by one endpoint and one only: the records <em>this</em> temple raised. There is no view,
 * anywhere, of anybody else's — not a filtered one, not a redacted one. The nearest thing to it is a
 * {@link BanFinding}, which arrives only as the result of an actual hire.
 *
 * @param staffProfileId the former employee this was raised against, so the screen can link back
 * @param fadesOn        when it stops appearing at hires. Shown because a temple ought to know that
 *                       its record has a life, and how much of it is left
 */
public record EmploymentBanView(
		UUID id,
		UUID staffProfileId,
		String personName,
		BanCategory category,
		String categoryLabel,
		String account,
		Instant raisedAt,
		String raisedBy,
		LocalDate fadesOn,
		boolean retracted,
		Instant retractedAt,
		String retractionReason) {
}
