package org.iskcon.kms.donation;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A donation our books call COMPLETED that the provider does not confirm (E7-S9 reconciliation) — the
 * kind of drift that must never go unnoticed when money is involved.
 */
public record ReconciliationMismatch(
		UUID donationId,
		String providerPaymentId,
		BigDecimal amountInr,
		String providerStatus) {
}
