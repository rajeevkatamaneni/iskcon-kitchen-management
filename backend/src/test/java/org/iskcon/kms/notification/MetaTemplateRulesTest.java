package org.iskcon.kms.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules Meta checks a template body against before any person reviews it, enforced on every
 * {@link NotificationTemplate} so that none reaches Meta breaking them (T-159).
 *
 * <p><strong>Why this exists.</strong> On 2026-09-12 South Bengaluru connected WhatsApp on staging and
 * Meta refused six of our twenty templates on the spot. Each refusal was a property of the sentence we
 * wrote, visible in the code the whole time, and nothing checked for it. A refusal costs more than it
 * looks: the message silently falls back to SMS for every temple, and nobody hears about it unless
 * they read the API's log.
 *
 * <p><strong>Where each rule comes from.</strong>
 * <ol>
 *   <li><strong>Not only parameters; no more than two newlines in a row; no more than ten emoji.</strong>
 *       Meta's own refusal of {@code temple_communication} on staging, word for word: "The message
 *       body can't have more than two consecutive newline characters, only have parameters, or have
 *       more than 10 emojis."</li>
 *   <li><strong>No parameter at the start or end.</strong> Meta's template review page lists "The
 *       message template cannot start or end with a parameter (dangling parameters are not allowed)"
 *       among its common rejection reasons
 *       (https://developers.facebook.com/documentation/business-messaging/whatsapp/templates/template-review),
 *       and staging refused {@code low_stock_digest} with "Variables can't be at the start or end of
 *       the template." Meta does not say whether a parameter followed only by a full stop counts as
 *       the end, and nothing we have sent settles it, so this treats it as the end: punctuation and
 *       spaces are stripped from both ends before looking.</li>
 *   <li><strong>Enough fixed words for the number of parameters.</strong> The same review page lists
 *       "The template contains too many variable parameters relative to the message length", and Meta
 *       publishes no number for it — not on that page, not on the components page, not in the
 *       template management guide. Two messaging providers publish one they found by testing:
 *       words plus parameters must be at least three times the parameters plus one (Syniverse,
 *       https://sdcsupport.syniverse.com/hc/en-us/articles/30569685832471 ; Mercuri,
 *       https://docs.mercuri.cx/features/templates/whatsapp-templates/whatsapp-template-minimum-words-parameters-ratio).
 *       That figure is consistent with everything staging showed: it rejects exactly the four Meta
 *       refused for length and passes the fourteen Meta accepted, which {@link #stagingOutcomes} pins.
 *       This test asks for a little more than that — <em>three fixed words for every parameter</em>,
 *       which is words plus parameters of at least four times the parameters. It is a round rule a
 *       person can apply while writing a sentence, it sits above the published figure by one word per
 *       parameter less one, and every template Meta accepted already meets it, so the margin costs no
 *       rewording of a template Meta holds.</li>
 * </ol>
 *
 * <p>A "word" is a run of non-space characters with at least one letter or digit in it, counted after
 * the placeholders are taken out, so a dash standing alone is not a word and "item(s)" is one.
 */
class MetaTemplateRulesTest {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\d+}}");
	private static final Pattern WORD = Pattern.compile("\\S*[\\p{L}\\p{N}]\\S*");
	/** Java 21's Unicode emoji property; the plain {@code IsEmoji} would count the digits 0–9. */
	private static final Pattern EMOJI = Pattern.compile("\\p{IsExtended_Pictographic}");
	private static final Pattern EDGE_PARAMETER =
			Pattern.compile("^[\\s\\p{P}]*\\{\\{\\d+}}|\\{\\{\\d+}}[\\s\\p{P}]*$");

	// ---- the three rules, as functions, so the staging calibration below can use them too --------

	/** Rule (a): what Meta's temple_communication refusal named. Empty when the body passes. */
	static List<String> shapeProblems(String body) {
		List<String> problems = new ArrayList<>();
		String fixed = PLACEHOLDER.matcher(body).replaceAll("");
		if (!fixed.codePoints().anyMatch(Character::isLetter)) {
			problems.add("the body is only parameters");
		}
		if (body.contains("\n\n\n")) {
			problems.add("more than two consecutive newlines");
		}
		long emoji = EMOJI.matcher(body).results().count();
		if (emoji > 10) {
			problems.add(emoji + " emoji, more than 10");
		}
		return problems;
	}

	/** Rule (b). */
	static boolean parameterAtAnEdge(String body) {
		return EDGE_PARAMETER.matcher(body).find();
	}

	static int parameters(String body) {
		return (int) PLACEHOLDER.matcher(body).results().count();
	}

	static int fixedWords(String body) {
		Matcher words = WORD.matcher(PLACEHOLDER.matcher(body).replaceAll(" "));
		return (int) words.results().count();
	}

	/** The figure two providers publish: words plus parameters at least 3n + 1. */
	static boolean meetsPublishedRatio(String body) {
		int n = parameters(body);
		return n == 0 || fixedWords(body) + n >= 3 * n + 1;
	}

	/** Rule (c), with this project's margin: at least three fixed words per parameter. */
	static boolean meetsOurRatio(String body) {
		int n = parameters(body);
		return fixedWords(body) >= 3 * n;
	}

	// ---- every template ---------------------------------------------------------------------------

	@Test
	@DisplayName("(a) no template body is only parameters, has three newlines in a row, or more than ten emoji")
	void shape() {
		for (NotificationTemplate template : NotificationTemplate.values()) {
			assertThat(shapeProblems(template.whatsappBodyText()))
					.as("%s (%s): %s", template, template.whatsappTemplateName(), template.whatsappBodyText())
					.isEmpty();
		}
	}

	@Test
	@DisplayName("(b) no template body starts or ends with a parameter")
	void noParameterAtAnEdge() {
		for (NotificationTemplate template : NotificationTemplate.values()) {
			assertThat(parameterAtAnEdge(template.whatsappBodyText()))
					.as("%s (%s) starts or ends with a parameter: %s",
							template, template.whatsappTemplateName(), template.whatsappBodyText())
					.isFalse();
		}
	}

	@Test
	@DisplayName("(c) every template body has at least three fixed words for each parameter")
	void enoughWordsForItsParameters() {
		for (NotificationTemplate template : NotificationTemplate.values()) {
			String body = template.whatsappBodyText();
			assertThat(meetsOurRatio(body))
					.as("%s (%s) has %d fixed words for %d parameters, needs at least %d: %s",
							template, template.whatsappTemplateName(), fixedWords(body), parameters(body),
							3 * parameters(body), body)
					.isTrue();
		}
	}

	// ---- the rules against what Meta actually did -------------------------------------------------

	/**
	 * The rules checked against Meta itself, which is the only evidence they model anything.
	 *
	 * <p>These six bodies are what staging submitted on 2026-09-12, copied from the code as it was,
	 * each with the reason Meta gave. If a rule here were loosened until one of them passed, it would
	 * have waved through a template Meta has already refused once.
	 */
	@Test
	@DisplayName("the rules refuse every body Meta refused on staging, for the reason Meta gave")
	void stagingOutcomes() {
		// "This template has too many variables for its length."
		for (String refusedForLength : List.of(
				"Reminder: your {{1}} shift at {{2}} is on {{3}} at {{4}}.",
				"Purchase order {{1}} for {{2}} is ready: {{3}}. Raised {{4}}, needed by {{5}}.",
				"Update about your {{1}} shift: {{2}}",
				"A message from {{1}} — {{2}}: {{3}} Read it here: {{4}}")) {
			assertThat(meetsPublishedRatio(refusedForLength)).as(refusedForLength).isFalse();
			assertThat(meetsOurRatio(refusedForLength)).as(refusedForLength).isFalse();
		}
		// "The message body can't have more than two consecutive newline characters, only have
		// parameters, or have more than 10 emojis."
		assertThat(shapeProblems("{{1}}")).containsExactly("the body is only parameters");
		// "Variables can't be at the start or end of the template."
		assertThat(parameterAtAnEdge("{{1}} item(s) at {{2}} are below their reorder level: {{3}}.")).isTrue();
	}

	/**
	 * And the other half: the published figure passes every template Meta accepted on staging, so it
	 * is not simply stricter than Meta. The six refused ones have since been rewritten, so they are
	 * excluded here by name rather than tested in their new form, which the three tests above do.
	 */
	@Test
	@DisplayName("the published ratio passes the fourteen templates Meta accepted on staging")
	void acceptedOnStagingPassTheRatio() {
		List<String> refusedOnStaging = List.of("shift_reminder", "po_delivery", "shift_broadcast",
				"temple_announcement", "temple_communication", "low_stock_digest");
		for (NotificationTemplate template : NotificationTemplate.values()) {
			if (refusedOnStaging.contains(template.whatsappTemplateName())
					|| template == NotificationTemplate.WHATSAPP_TEST) {
				continue; // rewritten by T-159 — see above
			}
			assertThat(meetsPublishedRatio(template.whatsappBodyText()))
					.as("%s was accepted by Meta", template.whatsappTemplateName()).isTrue();
		}
	}

	@Test
	@DisplayName("the emoji count does not mistake digits, rupee signs or dashes for emoji")
	void emojiCountIsNotFooledByOrdinaryCharacters() {
		assertThat(EMOJI.matcher("0123456789 # * ₹14,000 — ").results().count()).isZero();
		assertThat(shapeProblems("Hare Krishna 🙏🙏🙏🙏🙏🙏🙏🙏🙏🙏🙏 {{1}} thank you")).containsExactly("11 emoji, more than 10");
	}
}
