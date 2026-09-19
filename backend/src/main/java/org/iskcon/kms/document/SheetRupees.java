package org.iskcon.kms.document;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rupees on a printed sheet, written the way the screen writes them: the ₹ sign, Indian grouping —
 * ₹1,00,000 rather than ₹100000 — and paise only where there are any: "₹1,500", "₹71.20" (T-268).
 *
 * <p>The screen's rule is {@code money()} in {@code frontend/lib/format.ts} (Intl en-IN, no fraction
 * digits on a whole amount, two otherwise). The purchase order sheet used to print its own form,
 * "₹1500.00", so the same bag of rice cost "₹1,500 / bag" on the vendor page and "₹1500.00 / bag" on
 * the paper the vendor was handed. Rajeev's review found it (F1), and the conductor's ruling for T-260
 * was the same label in every view, so T-268 brought the sheet into line. The donations formatter's
 * comment gives the old reason for fixed places on documents (a PO is reconciled against an invoice,
 * so the trailing zeros matter). What reconciling needs is the paise when there are some, and those
 * are still printed in full: "₹71.20", never "₹71.2".
 *
 * <p>The grouping itself is {@link IndianNumbers}, shared with the donations formatter and with
 * {@code Quantities} since T-279. T-268 grouped by hand here because the JDK's en-IN formatter prints
 * "100,000"; the donations formatter used that JDK formatter and so printed lakhs wrong, and T-279
 * lifted this class's hand grouping out so that every caller gets it.
 *
 * <p>Rounded to paise, half up, before anything else, so a rate held to four places (V146:
 * {@code expected_price} is NUMERIC(14,4)) prints as the nearest paisa: ₹0.0712 per gm is "₹0.07".
 */
final class SheetRupees {

	private SheetRupees() {
	}

	static String format(BigDecimal amount) {
		BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
		// Whole rupees take no paise; anything else takes both digits, so 71.2 reads "71.20". The
		// sign goes before the ₹ ("-₹1,500"), as the screen's money() writes it, so it is taken off
		// here and the grouping is given the size alone.
		int decimals = value.remainder(BigDecimal.ONE).signum() == 0 ? 0 : 2;
		return (value.signum() < 0 ? "-₹" : "₹") + IndianNumbers.group(value.abs(), decimals, decimals);
	}
}
