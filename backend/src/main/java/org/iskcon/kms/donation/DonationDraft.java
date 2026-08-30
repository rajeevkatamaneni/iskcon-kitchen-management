package org.iskcon.kms.donation;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Everything needed to open a monetary donation record (E7). The donation stories build one of these
 * — one-time (E7-S2), a recurring cycle (E7-S3), or a gift towards a wish-list item (E7-S6) — and
 * hand it to {@link MonetaryDonationService#createDonation}.
 *
 * <p>A wish-list gift carries the item and nothing else about it. What was given is the amount, and
 * progress towards the item is money over its cost; there is no count of units, because the temple
 * buys the thing whole and a devotee may put in any part of the price.
 */
public record DonationDraft(
		String type,
		BigDecimal amountInr,
		String provider,
		String providerOrderId,
		String idempotencyKey,
		UUID wishlistItemId,
		UUID recurringPlanId,
		UUID donorAccountUserId,
		DonorDetails donor) {
}
