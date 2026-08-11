package org.iskcon.kms.donation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One line of the donations ledger (E7-S7). {@code donorDisplay} is anonymity-aware — "Anonymous"
 * for an anonymous gift, the name otherwise — and never carries PAN or contact. {@code category} is
 * one of ONE_TIME / RECURRING / WISHLIST / IN_KIND.
 */
public record LedgerRow(
		UUID id,
		LocalDate donatedOn,
		String category,
		String donorDisplay,
		BigDecimal amountInr,
		String currency,
		String paymentMode,
		String providerRef,
		String status,
		String linkedTo) {
}
