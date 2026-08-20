package org.iskcon.kms.ban;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Deciding whether the person being hired is the person on a ban record (B9).
 *
 * <p>Two layers, and the difference between them is the whole design.
 *
 * <p><b>The exact layer</b> compares a value against the same value. A PAN fingerprint is an HMAC
 * under a key with no tenant and no salt in it, so the same PAN produces the identical fingerprint at
 * every temple on the platform: a match on it is a match on the person, and it revealed nothing to
 * either side to find that out. The Aadhaar triple is the same idea with the issuer's own attestation
 * behind it. A phone number is compared exactly too, and is listed as exact honestly rather than
 * flatteringly — numbers get reassigned, so it is an exact comparison of a value that is not proof of
 * identity.
 *
 * <p><b>The fuzzy layer</b> is for the person who changed their details, which is the case the whole
 * feature exists for — somebody dismissed in Bengaluru does not arrive in Mayapur with the same phone
 * number. It scores the normalised name, and separately the normalised address, on trigram
 * similarity.
 *
 * <p><b>Neither layer blocks anything.</b> A finding is shown to the hiring admin with the raising
 * temple named and what they wrote, and the admin decides. That is not a softening of the feature; it
 * is the feature. A hard block would move the judgement from the person in the room — who can ring
 * the other temple and ask — to a threshold in this file.
 *
 * <p>Kept as pure static functions over values, with no database and no Spring, so the rule can be
 * read, argued with and tested on its own ({@code BanMatcherTest}). The one thing that must not
 * happen to a matcher like this is for it to become folklore nobody can check.
 */
public final class BanMatcher {

	/**
	 * How alike two names must be before the fuzzy layer will raise a finding.
	 *
	 * <p>PostgreSQL's pg_trgm defaults to 0.3 and would be useless here: at 0.3 almost any two Indian
	 * names sharing a common surname match, and a check that flags everybody is a check nobody reads.
	 * 0.55 was chosen against the cases that actually turn up, all of which are pinned in
	 * {@code BanMatcherTest} so that moving this number tells you what you have broken:
	 *
	 * <ul>
	 *   <li>"Gopal Das" vs "Gopala Das" scores 0.75 — a spelling of the same name, flagged.
	 *   <li>"Ramesh Kumar" vs "Ramesh Kumar Singh" scores 0.68 — a name given more fully, flagged.
	 *   <li>"Ramesh Kumar" vs "Suresh Kumar" scores 0.44 — two different people who share a very
	 *       common surname, not flagged.
	 * </ul>
	 *
	 * <p>The third is the one the number is really set by. Getting it wrong in that direction is a
	 * confident false positive against a devotee who has done nothing, in a community whose stated
	 * posture is to welcome everybody — which BL-6 named as the failure to design against.
	 */
	public static final double NAME_SIMILARITY_THRESHOLD = 0.55;

	/**
	 * Addresses are noisier than names — abbreviations, a missing pin code, a landmark instead of a
	 * street — so the bar is lower. It buys less than it looks, because an address on its own never
	 * raises a finding: a temple's own staff quarters, a hostel or a village name is an address a
	 * hundred unrelated people share. It only corroborates, and only ever appears beside a signal
	 * that stood up on its own.
	 */
	public static final double ADDRESS_SIMILARITY_THRESHOLD = 0.45;

	private BanMatcher() {
	}

	/**
	 * Compares one candidate against one ban record.
	 *
	 * @return the signals that matched, in the order they would be read out, or empty when this
	 *         record is not about this person as far as anything here can tell
	 */
	public static Optional<List<MatchSignal>> match(PersonSignals candidate, PersonSignals banned) {
		List<MatchSignal> signals = new ArrayList<>();

		if (candidate.panFingerprint() != null
				&& candidate.panFingerprint().equals(banned.panFingerprint())) {
			signals.add(MatchSignal.PAN);
		}

		if (candidate.aadhaar() != null && banned.aadhaar() != null
				&& candidate.aadhaar().last4().equals(banned.aadhaar().last4())
				&& candidate.aadhaar().dateOfBirth().equals(banned.aadhaar().dateOfBirth())
				&& PersonSignals.normalise(candidate.aadhaar().name())
						.equals(PersonSignals.normalise(banned.aadhaar().name()))) {
			signals.add(MatchSignal.AADHAAR);
		}

		if (candidate.phoneDigits() != null && candidate.phoneDigits().equals(banned.phoneDigits())) {
			signals.add(MatchSignal.PHONE);
		}

		boolean nameMatches =
				similarity(candidate.nameNormalised(), banned.nameNormalised()) >= NAME_SIMILARITY_THRESHOLD;
		if (nameMatches) {
			signals.add(MatchSignal.NAME);
		}

		// Corroboration only, and only when something else already stood up — see the threshold's
		// comment. Computed after the decision below is already made, never as part of making it.
		boolean somethingStoodUp = !signals.isEmpty();
		if (somethingStoodUp
				&& candidate.addressNormalised() != null
				&& banned.addressNormalised() != null
				&& similarity(candidate.addressNormalised(), banned.addressNormalised())
						>= ADDRESS_SIMILARITY_THRESHOLD) {
			signals.add(MatchSignal.ADDRESS);
		}

		return somethingStoodUp ? Optional.of(List.copyOf(signals)) : Optional.empty();
	}

	/** True when at least one of the signals is a comparison of a value against the same value. */
	public static boolean isExact(List<MatchSignal> signals) {
		return signals.stream().anyMatch(MatchSignal::exact);
	}

	/**
	 * Trigram similarity, the same rule PostgreSQL's pg_trgm implements: each word is padded with two
	 * leading spaces and one trailing space, cut into every three-character run, and the two sets
	 * compared as {@code |A ∩ B| / |A ∪ B|}.
	 *
	 * <p>Written here rather than in SQL on purpose. pg_trgm is an extension, and the migration role
	 * is deliberately not a superuser and holds no right to create one — a matching rule that only
	 * works if somebody remembers to install something on a database is a matching rule that will one
	 * day quietly stop matching. In Java it is twenty lines, it runs over a handful of candidate rows
	 * the blocking key already narrowed, and it can be unit tested without a database at all.
	 *
	 * <p>Trigrams rather than edit distance because the failure they forgive is the right one: a
	 * middle name added, a title dropped, a transliteration that gained a vowel. Edit distance
	 * punishes an inserted word by its whole length.
	 */
	public static double similarity(String left, String right) {
		if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
			return 0;
		}
		if (left.equals(right)) {
			return 1;
		}
		Set<String> a = trigrams(left);
		Set<String> b = trigrams(right);
		if (a.isEmpty() || b.isEmpty()) {
			return 0;
		}
		int shared = 0;
		for (String trigram : a) {
			if (b.contains(trigram)) {
				shared++;
			}
		}
		int union = a.size() + b.size() - shared;
		return union == 0 ? 0 : (double) shared / union;
	}

	private static Set<String> trigrams(String normalised) {
		Set<String> out = new LinkedHashSet<>();
		for (String word : normalised.split(" ")) {
			if (word.isEmpty()) {
				continue;
			}
			String padded = "  " + word + " ";
			for (int i = 0; i + 3 <= padded.length(); i++) {
				out.add(padded.substring(i, i + 3));
			}
		}
		return out;
	}
}
