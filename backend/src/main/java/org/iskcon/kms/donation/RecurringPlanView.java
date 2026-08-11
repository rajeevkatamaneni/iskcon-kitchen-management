package org.iskcon.kms.donation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A donor's recurring plan (E7-S3). {@code shortUrl} is the provider's mandate-authorization link. */
public record RecurringPlanView(
		UUID id,
		String frequency,
		BigDecimal amountInr,
		String status,
		String subscriptionId,
		String shortUrl,
		Instant createdAt) {
}
