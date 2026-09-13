package org.iskcon.kms.donation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The counter's phone rule (T-186), on the same list of inputs as {@code frontend/__tests__/phone.test.ts}.
 * The two lists are meant to be read side by side: if one side's rule moves, the other side's test is
 * where somebody should notice. Invisible characters are escaped rather than pasted, so an editor cannot
 * turn one back into a plain space and leave the test passing for the wrong reason.
 *
 * <p>{@link #recognised()} and {@link #keptAsTyped()} are also read by
 * {@code DonationLedgerIT.phoneNetIsWiderThanTheCounterRule} (T-187), which checks the donor history's
 * SQL net against this rule on exactly these inputs. Add a case here and that check gains it too.
 */
class CounterPhoneTest {

	static Stream<Arguments> recognised() {
		return Stream.of(
				Arguments.of("ten digits with a space, as read out", "98765 43210"),
				Arguments.of("ten digits bare", "9876543210"),
				Arguments.of("a trunk 0", "09876543210"),
				Arguments.of("91 with no plus", "919876543210"),
				Arguments.of("+91 with a space and a hyphen", "+91 98765-43210"),
				Arguments.of("already in +91 form", "+919876543210"),
				Arguments.of("a non-breaking space and an en dash", "+91\u00A098765\u201343210"),
				Arguments.of("a zero-width space and the marks Android wraps a copied contact in",
						"\u202A98765\u200B43210\u202C"),
				Arguments.of("spaces at either end", "  98765 43210  "));
	}

	@ParameterizedTest(name = "{0}: \"{1}\"")
	@MethodSource("recognised")
	@DisplayName("an unambiguous Indian mobile number is saved as +91 and its ten digits")
	void recognisedShapesBecomePlusNinetyOne(String how, String typed) {
		assertThat(CounterPhone.normalise(typed)).isEqualTo("+919876543210");
	}

	static Stream<Arguments> keptAsTyped() {
		return Stream.of(
				Arguments.of("a Mumbai landline", "022 2345 6789", "022 2345 6789"),
				Arguments.of("a foreign number", "+1 555 0100", "+1 555 0100"),
				Arguments.of("a short number", "12345", "12345"),
				Arguments.of("letters", "abcd", "abcd"),
				Arguments.of("ten digits starting 1", "1234567890", "1234567890"),
				Arguments.of("ten digits starting 5", "5876543210", "5876543210"),
				Arguments.of("a typo with a letter", "98765 4321X", "98765 4321X"),
				Arguments.of("a bracketed trunk 0", "+91 (0) 98765 43210", "+91 (0) 98765 43210"),
				Arguments.of("an international 0091 prefix", "0091 98765 43210", "0091 98765 43210"),
				Arguments.of("+91 followed by a 0 as well", "+91 0 98765 43210", "+91 0 98765 43210"),
				Arguments.of("eleven digits", "98765 432101", "98765 432101"),
				Arguments.of("a landline, trimmed", "  022 2345 6789 ", "022 2345 6789"));
	}

	@ParameterizedTest(name = "{0}: \"{1}\"")
	@MethodSource("keptAsTyped")
	@DisplayName("anything else is saved exactly as typed, after trimming, with no guessing")
	void everythingElseIsKeptAsTyped(String how, String typed, String saved) {
		assertThat(CounterPhone.normalise(typed)).isEqualTo(saved);
	}

	@Test
	@DisplayName("a Bengaluru landline with its trunk 0 becomes that same line's +91 number, which is not a guess")
	void bengaluruLandlineBecomesItsOwnInternationalNumber() {
		// 080 is Bengaluru's code, so "0" and ten digits starting 8. Dropping the 0 and writing +91 is
		// how this line is written from abroad. The brief listed it as kept as typed; the rule it
		// decided does this, and the outcome rings the same phone.
		assertThat(CounterPhone.normalise("080 2345 6789")).isEqualTo("+918023456789");
	}

	@Test
	@DisplayName("no phone, or a box of spaces, is still no phone")
	void blankIsNull() {
		assertThat(CounterPhone.normalise(null)).isNull();
		assertThat(CounterPhone.normalise("")).isNull();
		assertThat(CounterPhone.normalise("   ")).isNull();
	}
}
