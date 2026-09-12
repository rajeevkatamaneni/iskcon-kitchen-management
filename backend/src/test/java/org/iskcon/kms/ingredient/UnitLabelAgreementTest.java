package org.iskcon.kms.ingredient;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who is allowed to read {@link Unit#label()} — the plural word — without showing it a number.
 *
 * <p><strong>Read the name of this class literally.</strong> It does not check that the words are
 * right; {@link QuantitiesTest} does that, with a vector table. This checks one narrower thing: that
 * the no-argument {@link Unit#label()} is called only from places that have been looked at and found
 * to have no figure beside them. Everywhere a quantity <em>is</em> in scope must go through
 * {@code Unit.label(BigDecimal)} instead, because a word that has not been shown its number cannot
 * agree with it — that is the whole "1 pieces" defect (T-108 on the screens, T-144 here).
 *
 * <h2>Why a source scan, and why this shape of one</h2>
 *
 * <p>The screens are guarded by a regex in {@code __tests__/design-system.test.ts} that flags a
 * closing interpolation followed by a bare {@code unitLabel(} — a number and a label rendered as one
 * phrase by hand. Java has no equivalent tell. {@code "%s %s".formatted(n, u.label())} and
 * {@code new ScaledQuantity(raw, name, value, u.label())} are the shapes that actually produce the
 * bug here, and no regex recognises either as "a number next to a word".
 *
 * <p>So this inverts the test. Rather than trying to spot the bad composition, it enumerates every
 * call site of the plural label and holds that list fixed. The list is one line long across the
 * whole of {@code src/main} (it was four until T-148 fixed three of them), which is what makes this
 * practical: a second appearing is a thing a person should look at once, and a person looking at it
 * once is all this defect has ever needed.
 *
 * <p><strong>The one thing that makes the scan precise:</strong> twelve unrelated enums in this
 * codebase expose a {@code label()} — {@code BanCategory}, {@code LeaveType}, {@code JobTitle},
 * {@code PaymentMode} and the rest — so scanning for {@code .label()} across the tree matches
 * twenty-odd innocent lines and proves nothing. It is restricted to files that mention {@code Unit}
 * at all, and at that width it currently matches one line, which really is {@code Unit.label()} and
 * does not belong to another enum. If that ever stops being true the
 * failure is a false alarm on a new file, which is cheap; the alternative — a scan so loose nobody
 * believes it — is how a guard becomes noise and gets deleted.
 */
class UnitLabelAgreementTest {

	private static final Path SOURCE = Path.of("src/main/java/org/iskcon/kms");

	/**
	 * Every place the plural label is read without a number, and why that is right there.
	 *
	 * <p>Each entry is {@code path:line-content}. Matching on the content rather than the line
	 * number keeps this from failing every time somebody adds an import above it.
	 */
	private static final Set<String> ALLOWED = new LinkedHashSet<>(List.of(

			// An error message about a unit mismatch — "Rice is kept in Kg; this asks for L". It names
			// two units and counts neither.
			"ingredient/IngredientUnits.java|ref.name(), ref.canonical().label(), given.label(), ref.canonical().label())));"

			// ---- Removed by T-148, and why, so nobody puts them back. ----------------------------
			//
			// The purchase-order rate, "₹80 / pieces". This list used to allow it with the reasoning
			// that the rate is wrong "in a way label(count) cannot fix, because there is no count to
			// give it". That did not hold. A rate is a price for ONE of the unit — "per" is "for each
			// one" — so the count is there, and it is one: label(BigDecimal.ONE) says "piece" for a
			// count and "Kg" for a kilo, which is exactly the sheet's right answer. No third idea in
			// the formatter was needed. DocumentGenerationService asks for label(BigDecimal.ONE) now,
			// and DescribedPurchaseLineIT checks the printed sheet reads "/ piece" and "/ Kg".
			//
			// RecipeScaler's two lines, which gave the recipe scale preview "1 pieces". They were
			// listed here as a standing note rather than an approval, on the reasoning that the fix
			// would change ScaledQuantity's wire shape. It did not need to: displayUnit was always a
			// plain word for the screen, so the scaler now chooses that word from the rounded figure
			// it sends beside it, and the response has the same two fields of the same types.
			));

	@Test
	@DisplayName("nobody reads the plural label in a new place without it being looked at")
	void plainLabelCallSitesAreTheKnownOnes() throws IOException {
		List<String> found = new ArrayList<>();

		try (Stream<Path> files = Files.walk(SOURCE)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				String body = Files.readString(file);

				// Only files that mention Unit at all — see the class comment for why this narrowing
				// is what makes the scan precise rather than noisy.
				if (!body.contains("Unit")) {
					continue;
				}

				String relative = SOURCE.relativize(file).toString();
				for (String line : body.split("\n")) {
					String trimmed = line.trim();

					// The no-argument call only. label(count) is the correct one and is not flagged.
					if (trimmed.contains(".label()")) {
						found.add(relative + "|" + trimmed);
					}
				}
			}
		}

		assertThat(found)
				.as("""
						A place reads Unit's plural label without showing it a number.

						If a figure is printed in front of it, use unit.label(count) — that is the fix for
						"1 pieces" and the reason label(BigDecimal) exists. If the unit is genuinely being
						named with no count beside it (a column heading, a dropdown option), that is
						correct: add the line to ALLOWED in this file with a sentence saying which of those
						it is.

						A rate after a price is NOT one of those. "per piece" is a count of one, so it is
						unit.label(BigDecimal.ONE) — see DocumentGenerationService.
						""")
				.containsExactlyInAnyOrderElementsOf(ALLOWED);
	}

	@Test
	@DisplayName("the one place that prints a figure and a word together asks for agreement")
	void quantitiesAsksForAgreement() throws IOException {
		// The funnel. Every quantity on a job card, a recipe card, a purchase-order sheet and a work
		// order is rendered by Quantities.say(), so this single line is what makes all four documents
		// right. If it ever goes back to label() the vector table above catches the words, but this
		// catches the mechanism, and says in one place which line matters.
		String body = Files.readString(SOURCE.resolve("ingredient/Quantities.java"));

		assertThat(body)
				.as("Quantities.say() must choose the word from the figure it is about to print")
				.contains("unit.label(value)");
	}
}
