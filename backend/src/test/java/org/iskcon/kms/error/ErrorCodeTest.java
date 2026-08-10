package org.iskcon.kms.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Guards the properties that make error codes worth having.
 *
 * <p>These are cheap tests protecting an expensive mistake: a duplicated or reused code makes a
 * user's screenshot ambiguous, and technical language on screen sends temple staff to a support
 * call they cannot usefully have.
 */
class ErrorCodeTest {

	/**
	 * Words that mean nothing to a temple cook and often mean the internals leaked. Checked
	 * literally because the failure mode is copy-pasting a developer's mental model into a
	 * user-facing string, which is easy to do and hard to notice in review.
	 */
	private static final List<String> JARGON = List.of(
			"exception", "stack trace", "database", "constraint violation",
			"sql", "http", "jwt", "server", "endpoint", "payload",
			"unable to process", "invalid input syntax", "internal error");

	@Test
	@DisplayName("no two codes share a number")
	void codeNumbersAreUnique() {
		Set<Integer> seen = new HashSet<>();

		Stream.of(ErrorCode.values()).forEach(code ->
				assertThat(seen.add(code.number()))
						.as("code number %d is used by more than one error — a user quoting it "
								+ "would be ambiguous", code.number())
						.isTrue());
	}

	@ParameterizedTest
	@EnumSource(ErrorCode.class)
	@DisplayName("every message is plain language, free of technical jargon")
	void messagesAvoidJargon(ErrorCode code) {
		String text = (code.whatHappened() + " " + code.whatToDo()).toLowerCase();

		JARGON.forEach(term ->
				assertThat(text)
						.as("%s contains '%s' — the person reading this runs a temple kitchen, "
								+ "not a server", code.reference(), term)
						.doesNotContain(term));
	}

	@ParameterizedTest
	@EnumSource(ErrorCode.class)
	@DisplayName("every error tells the user what they can do next")
	void everyErrorOffersAnAction(ErrorCode code) {
		// An error that only describes the problem leaves someone stuck. There is almost always
		// something they can try, even if it is only "try again shortly".
		assertThat(code.whatToDo())
				.as("%s does not tell the user what to do", code.reference())
				.isNotBlank();
	}

	@ParameterizedTest
	@EnumSource(ErrorCode.class)
	@DisplayName("every message reads as a sentence, not a label")
	void messagesAreSentences(ErrorCode code) {
		assertThat(code.whatHappened())
				.as("%s should read as a complete sentence", code.reference())
				.endsWith(".");

		assertThat(code.whatToDo())
				.as("%s action should read as a complete sentence", code.reference())
				.endsWith(".");
	}

	@ParameterizedTest
	@EnumSource(ErrorCode.class)
	@DisplayName("codes render in the documented KMS-nnnn form")
	void referencesAreWellFormed(ErrorCode code) {
		// The shape matters: users read it aloud over the phone and type it into a search box.
		assertThat(code.reference()).matches("KMS-\\d{4}");
	}

	@ParameterizedTest
	@EnumSource(ErrorCode.class)
	@DisplayName("the code number agrees with the HTTP status it maps to")
	void numberingMatchesHttpStatus(ErrorCode code) {
		// 4xxx codes must be client errors, 5xxx server errors. Keeps the scheme meaningful
		// rather than decorative as codes are added over the coming epics.
		int family = code.number() / 1000;
		int httpFamily = code.httpStatus() / 100;

		assertThat(family)
				.as("%s is numbered %d but returns HTTP %d", code.reference(), code.number(), code.httpStatus())
				.isEqualTo(httpFamily);
	}

	@Test
	@DisplayName("no message blames the user or apologises theatrically")
	void toneIsNeutral() {
		List<String> banned = List.of("sorry", "oops", "unfortunately", "you failed", "!");

		Arrays.stream(ErrorCode.values()).forEach(code -> {
			String text = (code.whatHappened() + " " + code.whatToDo()).toLowerCase();
			banned.forEach(term ->
					assertThat(text)
							.as("%s contains '%s' — state what happened plainly instead",
									code.reference(), term)
							.doesNotContain(term));
		});
	}
}
