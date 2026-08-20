package org.iskcon.kms.ban;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One person reduced to the things two temples can compare (B9).
 *
 * <p>Built in exactly one place for both sides of the comparison — when a ban is raised, to fill the
 * record's own columns, and when somebody is hired, to check against them. That matters more than it
 * looks: two normalisations that disagree by a space or a full stop are two normalisations that
 * never match, and the failure is silent. One factory, used twice, cannot drift.
 *
 * <p>Nothing here is reversible into what it came from. The fingerprint is an HMAC, the tokens are a
 * blocking key, and the number is ten digits with no name attached.
 */
public record PersonSignals(
		String panFingerprint,
		String fullName,
		String nameNormalised,
		List<String> nameTokens,
		String phoneDigits,
		String addressNormalised,
		AadhaarIdentity aadhaar) {

	/**
	 * The shortest token worth blocking on. Two characters would put every "K" and "S" initial into
	 * the same bucket and turn the blocking key into a table scan.
	 */
	private static final int SHORTEST_USEFUL_TOKEN = 3;

	/** The last ten digits of an Indian number, so +919876543210 and 09876543210 are one value. */
	private static final int PHONE_DIGITS_COMPARED = 10;

	public static PersonSignals of(
			String panFingerprint, String fullName, String phone, String address, AadhaarIdentity aadhaar) {

		String normalisedName = normalise(fullName);
		return new PersonSignals(
				blankToNull(panFingerprint),
				fullName == null ? "" : fullName.trim(),
				normalisedName,
				tokens(normalisedName),
				phoneDigits(phone),
				blankToNull(normalise(address)),
				aadhaar != null && aadhaar.isComplete() ? aadhaar : null);
	}

	/**
	 * Lowercased, everything that is not a letter or a digit turned into a space, and runs of space
	 * collapsed. Deliberately blunt: "Sri Ramesh Kumar", "SRI RAMESH KUMAR." and "Sri  Ramesh Kumar"
	 * are one person written three ways, and a comparison that tells them apart is a comparison that
	 * fails at the one moment it is needed.
	 */
	public static String normalise(String value) {
		if (value == null) {
			return "";
		}
		StringBuilder out = new StringBuilder(value.length());
		boolean pendingSpace = false;
		for (char c : value.toLowerCase(Locale.ROOT).toCharArray()) {
			if (Character.isLetterOrDigit(c)) {
				if (pendingSpace && out.length() > 0) {
					out.append(' ');
				}
				pendingSpace = false;
				out.append(c);
			} else {
				pendingSpace = true;
			}
		}
		return out.toString();
	}

	/** The normalised name split into the tokens long enough to be worth blocking on. */
	public static List<String> tokens(String normalised) {
		Set<String> distinct = new LinkedHashSet<>();
		for (String token : normalised.split(" ")) {
			if (token.length() >= SHORTEST_USEFUL_TOKEN) {
				distinct.add(token);
			}
		}
		return List.copyOf(distinct);
	}

	/** Digits only, last ten. Null when there is no number, or too short a one to compare. */
	public static String phoneDigits(String phone) {
		if (phone == null) {
			return null;
		}
		String digits = phone.replaceAll("[^0-9]", "");
		return digits.length() < PHONE_DIGITS_COMPARED
				? null
				: digits.substring(digits.length() - PHONE_DIGITS_COMPARED);
	}

	/** For the SQL blocking key, which wants an array and not a list. */
	public String[] nameTokenArray() {
		return nameTokens.toArray(String[]::new);
	}

	public List<String> nameTokensOrEmpty() {
		return nameTokens == null ? Collections.emptyList() : nameTokens;
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s;
	}
}
