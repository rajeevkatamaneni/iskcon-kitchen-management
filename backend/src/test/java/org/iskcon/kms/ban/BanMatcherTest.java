package org.iskcon.kms.ban;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The matching rule on its own, without a database (B9).
 *
 * <p>The scores below are pinned deliberately. A similarity threshold that nobody can check is
 * folklore, and this one decides whether a devotee who has done nothing wrong is flagged in front of
 * the administrator about to employ them. Moving {@code NAME_SIMILARITY_THRESHOLD} should tell you
 * exactly which of these cases you have changed your mind about.
 */
class BanMatcherTest {

	@Nested
	@DisplayName("the trigram score, against the names it was actually tuned on")
	class Scores {

		@Test
		@DisplayName("a spelling of the same name is well over the threshold")
		void sameNameSpeltDifferently() {
			assertThat(BanMatcher.similarity("gopal das", "gopala das")).isCloseTo(0.75, within(0.01));
		}

		@Test
		@DisplayName("a name given more fully is over the threshold")
		void nameGivenMoreFully() {
			assertThat(BanMatcher.similarity("ramesh kumar", "ramesh kumar singh"))
					.isCloseTo(0.684, within(0.01));
		}

		@Test
		@DisplayName("two different people sharing a very common surname are not")
		void differentPeopleSharingASurname() {
			double score = BanMatcher.similarity("ramesh kumar", "suresh kumar");
			assertThat(score).isCloseTo(0.444, within(0.01));
			assertThat(score)
					.as("the case the threshold is really set by — a confident false positive here is "
							+ "the failure the whole feature was designed against")
					.isLessThan(BanMatcher.NAME_SIMILARITY_THRESHOLD);
		}

		@Test
		@DisplayName("word order does not matter, because a trigram bag has none")
		void wordOrder() {
			assertThat(BanMatcher.similarity("ramesh kumar", "kumar ramesh")).isEqualTo(1.0);
		}

		@Test
		@DisplayName("unrelated names score near nothing")
		void unrelated() {
			assertThat(BanMatcher.similarity("gopal das", "priya sharma")).isLessThan(0.1);
		}
	}

	@Nested
	@DisplayName("what raises a finding")
	class Findings {

		@Test
		@DisplayName("an identical PAN matches even when nothing else about the person does")
		void panAlone() {
			PersonSignals banned = PersonSignals.of(
					"fingerprint-1", "Ramesh Kumar", "+919876500011", "12 MG Road, Bengaluru", null);
			PersonSignals candidate = PersonSignals.of(
					"fingerprint-1", "R K Sharma", "+919000000000", "Mayapur", null);

			Optional<List<MatchSignal>> signals = BanMatcher.match(candidate, banned);

			assertThat(signals).contains(List.of(MatchSignal.PAN));
			assertThat(BanMatcher.isExact(signals.orElseThrow())).isTrue();
		}

		@Test
		@DisplayName("a changed phone and address still flag on the name, and only fuzzily")
		void changedDetailsStillFlag() {
			PersonSignals banned = PersonSignals.of(
					null, "Ramesh Kumar", "+919876500011", "12 MG Road, Bengaluru", null);
			PersonSignals candidate = PersonSignals.of(
					null, "Ramesh Kumar Singh", "+919812300099", "44 Temple Street, Mayapur", null);

			List<MatchSignal> signals = BanMatcher.match(candidate, banned).orElseThrow();

			assertThat(signals).containsExactly(MatchSignal.NAME);
			assertThat(BanMatcher.isExact(signals))
					.as("nothing exact matched, so the finding must not read as certainty")
					.isFalse();
		}

		@Test
		@DisplayName("the same address corroborates a name, and never raises a finding on its own")
		void addressOnlyCorroborates() {
			PersonSignals banned = PersonSignals.of(
					null, "Ramesh Kumar", null, "12 MG Road, Bengaluru", null);

			PersonSignals sameName = PersonSignals.of(
					null, "Ramesh Kumar", null, "12 M G Road Bengaluru", null);
			assertThat(BanMatcher.match(sameName, banned).orElseThrow())
					.containsExactly(MatchSignal.NAME, MatchSignal.ADDRESS);

			// A hostel, a village, or the temple's own staff quarters. Hundreds of unrelated people
			// share an address, so on its own it is not a finding at all.
			PersonSignals sameAddressOnly = PersonSignals.of(
					null, "Priya Sharma", null, "12 MG Road, Bengaluru", null);
			assertThat(BanMatcher.match(sameAddressOnly, banned)).isEmpty();
		}

		@Test
		@DisplayName("a reused phone number matches exactly, and is still not proof of identity")
		void phoneAlone() {
			PersonSignals banned = PersonSignals.of(null, "Ramesh Kumar", "+91 98765 00011", null, null);
			PersonSignals candidate = PersonSignals.of(null, "Priya Sharma", "09876500011", null, null);

			assertThat(BanMatcher.match(candidate, banned).orElseThrow())
					.as("formatting must not decide this — the last ten digits are the number")
					.containsExactly(MatchSignal.PHONE);
		}

		@Test
		@DisplayName("the Aadhaar triple matches as a triple, never as two thirds of one")
		void aadhaarTriple() {
			AadhaarIdentity theirs = new AadhaarIdentity("Ramesh Kumar", LocalDate.of(1985, 6, 12), "4321");
			PersonSignals banned = PersonSignals.of(null, "Ramesh Kumar", null, null, theirs);

			PersonSignals same = PersonSignals.of(null, "R Kumar", null, null,
					new AadhaarIdentity("RAMESH  KUMAR", LocalDate.of(1985, 6, 12), "4321"));
			assertThat(BanMatcher.match(same, banned).orElseThrow()).contains(MatchSignal.AADHAAR);

			PersonSignals differentBirthday = PersonSignals.of(null, "Priya Sharma", null, null,
					new AadhaarIdentity("Ramesh Kumar", LocalDate.of(1990, 1, 1), "4321"));
			assertThat(BanMatcher.match(differentBirthday, banned)).isEmpty();
		}

		@Test
		@DisplayName("two unrelated people produce nothing at all")
		void noFinding() {
			PersonSignals banned = PersonSignals.of(
					"fingerprint-1", "Ramesh Kumar", "+919876500011", "12 MG Road, Bengaluru", null);
			PersonSignals candidate = PersonSignals.of(
					"fingerprint-2", "Priya Sharma", "+919000000000", "44 Temple Street, Mayapur", null);

			assertThat(BanMatcher.match(candidate, banned)).isEmpty();
		}
	}

	@Nested
	@DisplayName("normalisation, which both sides of every comparison go through")
	class Normalisation {

		@Test
		@DisplayName("case, punctuation and repeated spaces are not differences between people")
		void normalise() {
			assertThat(PersonSignals.normalise("  Sri  RAMESH Kumar.  ")).isEqualTo("sri ramesh kumar");
		}

		@Test
		@DisplayName("blocking tokens skip the ones too short to narrow anything")
		void tokens() {
			assertThat(PersonSignals.tokens("r k ramesh kumar")).containsExactly("ramesh", "kumar");
		}

		@Test
		@DisplayName("a number too short to be a phone number is not compared at all")
		void shortPhone() {
			assertThat(PersonSignals.phoneDigits("+9198765")).isNull();
			assertThat(PersonSignals.phoneDigits("+919876500011")).isEqualTo("9876500011");
		}
	}
}
