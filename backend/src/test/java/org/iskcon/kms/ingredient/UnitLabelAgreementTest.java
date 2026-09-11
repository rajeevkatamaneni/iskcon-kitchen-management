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
 * call site of the plural label and holds that list fixed. The list is four lines long across the
 * whole of {@code src/main}, which is what makes this practical: a fifth appearing is a thing a
 * person should look at once, and a person looking at it once is all this defect has ever needed.
 *
 * <p><strong>The one thing that makes the scan precise:</strong> twelve unrelated enums in this
 * codebase expose a {@code label()} — {@code BanCategory}, {@code LeaveType}, {@code JobTitle},
 * {@code PaymentMode} and the rest — so scanning for {@code .label()} across the tree matches
 * twenty-odd innocent lines and proves nothing. It is restricted to files that mention {@code Unit}
 * at all, and at that width it currently matches four lines, all four of which really are
 * {@code Unit.label()} and none of which belongs to another enum. If that ever stops being true the
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

			// A price per unit of the thing, on a purchase-order sheet: "₹120 / Kg". There is a number
			// in the line but it is the money, not a count of the unit — "per" names the unit itself,
			// and the unit named after "per" wants the singular whatever the figures around it say.
			// "₹80 / pieces" is wrong, but it is wrong in a way label(count) cannot fix, because there
			// is no count to give it. T-108 found the same three strings on the purchase-order screen
			// ("Price paid per pieces of Plastic stool") and reported them rather than inventing a
			// third idea in the shared formatter. Same decision here, same reason: see T-144's proof.
			"document/DocumentGenerationService.java|price = money(l.expectedPrice()) + \" / \" + Unit.valueOf(l.unit()).label();",

			// An error message about a unit mismatch — "Rice is kept in Kg; this asks for L". It names
			// two units and counts neither.
			"ingredient/IngredientUnits.java|ref.name(), ref.canonical().label(), given.label(), ref.canonical().label())));",

			// ---- The two below are NOT a clean bill of health. -----------------------------------
			//
			// RecipeScaler hands the recipe scale preview a number and a word in two separate record
			// fields, and app/recipes/[id]/page.tsx:360 prints them straight back out as one phrase:
			//
			//     `${scaled.ingredients[i]?.displayQuantity} ${scaled.ingredients[i]?.displayUnit}`
			//
			// So a recipe line that scales to one of a counted thing reads "1 pieces" on that screen
			// today. It is the same defect in a third place, and it is invisible to both of the other
			// guards: the frontend's regex only knows about unitLabel(), which this phrase does not
			// call, and the word is chosen in Java where that regex cannot see it.
			//
			// It is left alone deliberately. RecipeScaler and that page are both outside T-144's
			// contract, and the fix is not the one-liner it looks like — ScaledQuantity is a wire
			// type, so changing displayUnit changes an API response and the screen that reads it, in
			// one step, across the seam. It wants its own task. Reported in T-144's proof.
			//
			// These two entries are therefore a standing note, not an approval. When somebody does fix
			// it, this test fails and they delete these two lines — which is the point of listing them
			// here rather than in a comment nobody runs.
			"recipe/RecipeScaler.java|return new ScaledQuantity(raw, unit.name(), round(raw), unit.label());",
			"recipe/RecipeScaler.java|return new ScaledQuantity(raw, unit.name(), round(displayValue), displayUnit.label());"));

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
						named with no count beside it (a column heading, a dropdown option, a "/ Kg" after
						a price), that is correct: add the line to ALLOWED in this file with a sentence
						saying which of those it is.

						If this failed because you FIXED RecipeScaler, delete its two entries from ALLOWED.
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
