package org.iskcon.kms.donation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One gift, in full, for the screen that decides whether to receipt it (T-110).
 *
 * <p>Wider than {@link LedgerRow} on purpose, and the difference is the whole reason this record
 * exists. The ledger is a list somebody scans down and its donor column is anonymity-safe by
 * construction — it never carries PII, export included, because a hundred rows on one screen is a
 * hundred chances to leak one. This is one gift, opened deliberately, behind {@code VIEW_DONATIONS},
 * by somebody about to put the temple's name on a tax document made out to this person. Withholding
 * the address they gave for exactly that purpose would make the screen useless for the one job it
 * has.
 *
 * <p>An anonymous gift still shows nothing, and needs no code here to arrange it: V38's CHECK
 * guarantees such a row holds no name, no contact, no address and no PAN. There is nothing to
 * withhold.
 *
 * @param amountInr        what the donor actually paid, and for a gift of goods the temple's own
 *                         estimate of worth instead. Never re-derived from
 *                         {@code wishlist_applied_inr}: T-081 kept this column meaning one payment
 *                         precisely so that one payment produces one receipt.
 * @param wishlistApplied  how much of that payment reached the wish-list item it names, or null
 *                         where all of it did. On this screen for one reason — so the person reading
 *                         it understands why the receipt's figure is larger than the item's progress
 *                         — and it never reaches the receipt itself.
 * @param hasPan           whether a PAN was captured, without carrying it. The PAN itself comes only
 *                         from the existing reveal endpoint, which audits every read.
 * @param receiptNumber    the permanent number, or null where no receipt has been issued yet.
 * @param temple80gApproved whether the temple holds 80G registration, which together with the kind
 *                         of gift decides what a receipt for it can honestly claim.
 * @param canBeReceipted   false for a struck gift, which the temple has said it did not receive. The
 *                         screen withholds the control rather than offering one that refuses — the
 *                         same call the ledger already makes about the Void button.
 */
public record DonationDetail(
		UUID id,
		String type,
		String category,
		LocalDate donatedOn,
		String status,
		boolean voided,
		String voidReason,
		BigDecimal amountInr,
		BigDecimal wishlistApplied,
		String currency,
		String paymentMode,
		String providerRef,
		String linkedTo,
		boolean anonymous,
		String donorName,
		String donorPhone,
		String donorEmail,
		String donorAddress,
		boolean wants80g,
		boolean hasPan,
		String notes,
		Instant acknowledgedAt,
		String receiptNumber,
		Instant receiptIssuedAt,
		boolean temple80gApproved,
		boolean canBeReceipted) {
}
