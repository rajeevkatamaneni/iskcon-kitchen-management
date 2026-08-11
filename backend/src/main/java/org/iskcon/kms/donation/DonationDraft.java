package org.iskcon.kms.donation;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Everything needed to open a monetary donation record (E7). The donation stories build one of these
 * — one-time (E7-S2), a recurring cycle (E7-S3), or a wish-list sponsorship (E7-S6) — and hand it to
 * {@link MonetaryDonationService#createDonation}.
 */
public record DonationDraft(
		String type,
		BigDecimal amountInr,
		String provider,
		String providerOrderId,
		String idempotencyKey,
		UUID wishlistItemId,
		Integer wishlistQuantity,
		UUID recurringPlanId,
		UUID donorAccountUserId,
		DonorDetails donor) {
}
