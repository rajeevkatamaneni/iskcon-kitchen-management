package org.iskcon.kms.donation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Rupees written the way a devotee in India reads them: the ₹ sign, Indian grouping — ₹1,40,000
 * rather than ₹140,000 — and paise only where there are any.
 *
 * <p>Both callers are places where money is put in front of a person rather than into a sum: the
 * donations ledger's account of a gift that went two ways, and the thank-you note that tells a donor
 * how their gift was split (T-081). Whole rupees are the overwhelming case in both, and
 * "₹14,000.00" in a thank-you note reads like a receipt printed by a machine — which is the one tone
 * that message must not have.
 *
 * <p>Not used for documents. {@code DocumentGenerationService} formats its own to two fixed places,
 * on purpose: a purchase order is a figure somebody will reconcile against an invoice, and there the
 * trailing zeros are the point.
 */
final class Rupees {

	private Rupees() {
	}

	static String format(BigDecimal amount) {
		BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
		// A fresh formatter per call. NumberFormat is not thread-safe, and these are rare paths — a
		// shared static instance would be a data race saved from nothing measurable.
		NumberFormat format = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"));
		int digits = value.scale() > 0 ? 2 : 0;
		format.setMinimumFractionDigits(digits);
		format.setMaximumFractionDigits(digits);
		return "₹" + format.format(value);
	}
}
