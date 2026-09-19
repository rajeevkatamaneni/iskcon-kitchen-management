package org.iskcon.kms.document;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A number written with Indian digit grouping: the last three digits, then pairs — "99,999",
 * "1,00,000", "12,34,567", "1,00,00,000" (T-279). The one place the backend groups a figure for a
 * person to read; every rupee amount and every quantity goes through it.
 *
 * <p><strong>Why this exists: the JDK cannot do it.</strong> {@code NumberFormat} for {@code en-IN}
 * prints 1,00,000 as "100,000" and 1,23,45,678.5 as "12,345,678.5" (measured on JDK 21.0.12, T-279;
 * first found by T-268's failing test). {@code DecimalFormat} keeps a single grouping size, so the
 * CLDR pattern {@code #,##,##0.###} collapses to groups of three. Below ₹1 lakh the two agree, which
 * is why this stayed hidden: every figure in every test was under a lakh. Rajeev's review found it
 * (F6) on real amounts — a temple's donations and bulk purchases pass a lakh routinely.
 *
 * <p>Before this, four places grouped figures and only one got lakhs right: the donation formatter
 * (the thank-you WhatsApp text, the ledger's split-gift label, the 80G receipt's amount), the PO
 * sheet's rupees (grouped by hand in T-268, correctly), the PO sheet's pack counts, and
 * {@code Quantities} (every quantity on a job card, work order, low-stock message and PO sheet). They
 * all call this now, so the rule is stated once.
 *
 * <p><strong>What it deliberately does the way {@code NumberFormat} did</strong>, so nothing a
 * caller printed below a lakh moves by a character:
 *
 * <ul>
 *   <li>Rounding is half-even, {@code NumberFormat}'s default. The money callers round to paise
 *       half-up themselves first, so for them this rounding never has anything left to do.
 *   <li>Trailing fraction zeros are dropped down to {@code minDecimals}: with 0 and 3, 2.500 is
 *       "2.5" and 4.000 is "4".
 *   <li>A negative figure that rounds to nothing keeps its sign ("-0"), as {@code DecimalFormat}
 *       does. Nobody should be shown one, but a quiet difference between two formatters is exactly
 *       the kind of near-agreement {@code IndianNumbersTest} exists to rule out.
 * </ul>
 *
 * <p>The browser's side of the same rule is {@code Intl.NumberFormat("en-IN")}, which does group in
 * lakhs; {@code frontend/__tests__/money-indian-grouping.test.ts} pins it at the same boundaries.
 *
 * <p>Public, and in {@code document}, because {@code donation} and {@code ingredient} both need it
 * and neither is the other's natural home; the PO sheet was the first caller to get it right.
 */
public final class IndianNumbers {

	private IndianNumbers() {
	}

	/**
	 * {@code value} with Indian grouping, at least {@code minDecimals} and at most
	 * {@code maxDecimals} places after the point. A negative figure starts with "-"; no currency
	 * sign is added, because where the sign goes relative to "₹" is the caller's decision.
	 */
	public static String group(BigDecimal value, int minDecimals, int maxDecimals) {
		if (minDecimals < 0 || maxDecimals < minDecimals) {
			throw new IllegalArgumentException("decimals " + minDecimals + ".." + maxDecimals);
		}
		boolean negative = value.signum() < 0;
		String plain = value.abs().setScale(maxDecimals, RoundingMode.HALF_EVEN).toPlainString();

		int dot = plain.indexOf('.');
		String whole = dot < 0 ? plain : plain.substring(0, dot);
		String fraction = dot < 0 ? "" : plain.substring(dot + 1);
		int keep = fraction.length();
		while (keep > minDecimals && fraction.charAt(keep - 1) == '0') {
			keep--;
		}
		fraction = fraction.substring(0, keep);

		return (negative ? "-" : "") + groupDigits(whole) + (fraction.isEmpty() ? "" : "." + fraction);
	}

	/** "1234567" as "12,34,567": the last three digits, then pairs from the right. */
	private static String groupDigits(String digits) {
		if (digits.length() <= 3) {
			return digits;
		}
		String head = digits.substring(0, digits.length() - 3);
		StringBuilder out = new StringBuilder();
		int first = head.length() % 2;
		if (first > 0) {
			out.append(head, 0, first);
		}
		for (int i = first; i < head.length(); i += 2) {
			if (out.length() > 0) {
				out.append(',');
			}
			out.append(head, i, i + 2);
		}
		return out.append(',').append(digits, digits.length() - 3, digits.length()).toString();
	}
}
