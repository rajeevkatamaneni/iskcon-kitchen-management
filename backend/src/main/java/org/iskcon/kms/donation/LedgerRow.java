package org.iskcon.kms.donation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One line of the donations ledger (E7-S7). {@code donorDisplay} is anonymity-aware — "Anonymous"
 * for an anonymous gift, the name otherwise — and never carries PAN or contact. {@code category} is
 * one of ONE_TIME / RECURRING / WISHLIST / IN_KIND.
 *
 * <p>{@code voided} is a field of its own rather than a value of {@code status} (T-012), because
 * {@code status} says how the payment went and a gift can perfectly well have completed and then
 * been struck as wrongly recorded. Folding the two would make one column answer two questions and
 * lose whichever was asked second. A voided gift stays on this list, marked, and is excluded from
 * the period summary above it — the ledger is a record of what was written down, the tiles are what
 * the temple reports it received, and those are different questions.
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
		String linkedTo,
		boolean voided,
		String voidReason) {
}
