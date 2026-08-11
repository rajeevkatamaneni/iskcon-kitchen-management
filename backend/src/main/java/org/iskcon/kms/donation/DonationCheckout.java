package org.iskcon.kms.donation;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What the client needs to open hosted checkout for a just-created PENDING donation (E7-S2): the
 * provider order id, the publishable key, and the amount. Confirmation comes later by webhook.
 */
public record DonationCheckout(
		UUID donationId,
		String orderId,
		String publicKey,
		BigDecimal amountInr,
		String currency,
		String provider) {
}
