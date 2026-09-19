package org.iskcon.kms.donation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.iskcon.kms.document.IndianNumbers;

/**
 * Rupees written the way a devotee in India reads them: the ₹ sign, Indian grouping — ₹1,40,000
 * rather than ₹140,000 — and paise only where there are any.
 *
 * <p>The grouping is {@link IndianNumbers}, shared with the PO sheet and every quantity (T-279).
 *
 * <p>Both callers are places where money is put in front of a person rather than into a sum: the
 * donations ledger's account of a gift that went two ways, and the thank-you note that tells a donor
 * how their gift was split (T-081). Whole rupees are the overwhelming case in both, and
 * "₹14,000.00" in a thank-you note reads like a receipt printed by a machine — which is the one tone
 * that message must not have.
 *
 * <p>A third caller has joined since that was written: the 80G receipt's amount
 * ({@code DonationReceiptService}). This paragraph used to say the class was not used for documents,
 * and that the PO sheet kept two fixed places; neither has been true since T-268 gave the sheet
 * {@code SheetRupees}, which writes the same form (corrected T-279).
 */
final class Rupees {

	private Rupees() {
	}

	static String format(BigDecimal amount) {
		BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
		int digits = value.scale() > 0 ? 2 : 0;
		// Grouped by IndianNumbers, not NumberFormat: the JDK's en-IN formatter writes a lakh as
		// "₹100,000", so a donor giving ₹1,50,000 was thanked for "₹150,000" (T-279). Below a lakh the
		// two print the same characters, which is why nobody saw it.
		//
		// A negative amount keeps the form NumberFormat gave it, "₹-500", on purpose: T-279 changed
		// the grouping only. Neither caller passes one today (the remainder of a split gift is never
		// below zero), and if one ever does, "-₹500" is the screen's form to move to.
		return "₹" + IndianNumbers.group(value, digits, digits);
	}
}
