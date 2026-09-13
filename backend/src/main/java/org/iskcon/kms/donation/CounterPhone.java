package org.iskcon.kms.donation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The donor's phone on a gift recorded at the counter, saved in {@code +91} form when, and only when,
 * what was typed can mean nothing else (T-186).
 *
 * <p><strong>Why this exists.</strong> My donations (T-179) lists a counter gift to a volunteer only
 * when the stored phone, with T-157's separators removed, is <em>identical</em> to the number Firebase
 * verified — always E.164, {@code +919876543210}. The counter form stored what the office typed, and
 * the office types a donor's number the way it is read out: {@code 98765 43210}. That gift never
 * reached its donor's list. Loosening the match was ruled out in T-179 for a good reason — comparing
 * the last ten digits is how one person's PAN-bearing receipt reaches another person's phone — so the
 * fix is at the other end: write the number properly when it is first written.
 *
 * <p><strong>The rule, all of it.</strong> Remove T-157's separators, then accept exactly these shapes
 * of an Indian mobile number, whose ten digits start 6, 7, 8 or 9:
 *
 * <ul>
 *   <li>the ten digits alone — {@code 98765 43210};
 *   <li>a trunk {@code 0} before them — {@code 09876543210};
 *   <li>{@code 91} or {@code +91} before them — {@code 919876543210}, {@code +91 98765-43210}.
 * </ul>
 *
 * <p>Each becomes {@code +91} and the ten digits. <strong>Anything else is saved exactly as typed</strong>
 * (trimmed, as before): a Mumbai landline such as {@code 022 2345 6789}, a foreign number, a short number, a
 * number with a letter in it, {@code 0091 …}, a bracketed {@code (0)}. Each of those has a plausible
 * reading and a plausible wrong one, and a wrong guess here is worse than no guess: it stores a
 * well-formed number that rings somebody else, and on My donations it would hand that somebody the
 * receipt. Left as typed, the gift simply does not match anyone, which is what happened before.
 *
 * <p><strong>A landline can match, and that is safe.</strong> A Bengaluru landline typed with its trunk
 * 0, {@code 080 2345 6789}, is a 0 and ten digits starting 8, so it becomes {@code +918023456789}.
 * That is the same line's correct international number, not a guess: every Indian number, mobile or
 * landline, is written internationally by dropping the trunk 0 and putting +91 in front. It can never
 * match a volunteer on My donations, because Firebase only verifies mobiles. Landlines whose area code
 * starts 1–5 (Delhi 011, Mumbai 022, Chennai 044) stay as typed, as does a ten-digit number starting
 * 1–5. The one wrong reading the rule allows is a foreign ten-digit number typed without its country
 * code, such as an American {@code 617 555 0100}; at an Indian temple's counter that is the rare case.
 *
 * <p><strong>Not a validator.</strong> Nothing is refused. {@code RecordDonationRequest.donorPhone}
 * keeps its length limit and deliberately gains no E.164 {@code @Pattern}: an office recording a gift
 * from somebody who gave only a landline must still be able to record it.
 *
 * <p><strong>Its twin.</strong> {@code normalizeIndianMobile} in {@code frontend/lib/phone.ts} applies
 * the same rule so the counter screen can show "Saved as +91 98765 43210" without asking the server.
 * The server is the authority — the screen's copy is for showing — and the two are held together by
 * the same input list in {@code CounterPhoneTest} and {@code phone.test.ts}.
 *
 * <p><strong>New gifts only.</strong> Rows recorded before this keep what was typed. Whether to rewrite
 * them is a separate decision (it widens who can download a receipt), and it is Rajeev's.
 */
final class CounterPhone {

	/**
	 * T-157's separators, copied from {@code config.PhoneNumberDeserializer.SEPARATORS}, whose own
	 * {@code normalise} is package-private. Copied rather than widened, as {@code MyDonationsService}
	 * copies it, because the two must remove the same characters: a separator this removed and My
	 * donations kept would store a number that still never matched.
	 */
	private static final Pattern SEPARATORS = Pattern.compile("[\\s\\p{Z}\\p{Pd}\\p{Cf}]");

	/**
	 * {@code [0-9]} rather than {@code \d}: {@code \d} is ASCII in Java by default and Unicode in some
	 * other engines, and a Devanagari digit is not something this should quietly reinterpret.
	 */
	private static final Pattern INDIAN_MOBILE = Pattern.compile("^(?:\\+91|91|0)?([6-9][0-9]{9})$");

	private CounterPhone() {
	}

	/**
	 * What to store for a phone typed at the counter: {@code +91} and ten digits when the rule above
	 * recognises it, otherwise the typed value trimmed. Null or blank is null, as it was before.
	 */
	static String normalise(String typed) {
		if (typed == null) {
			return null;
		}
		String trimmed = typed.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		Matcher mobile = INDIAN_MOBILE.matcher(SEPARATORS.matcher(trimmed).replaceAll(""));
		return mobile.matches() ? "+91" + mobile.group(1) : trimmed;
	}
}
