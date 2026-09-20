package org.iskcon.kms.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.iskcon.kms.ingredient.Unit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scaling math (E2-S3): linear ratio, unit promotion, and festival-scale precision.
 *
 * <p><strong>Which unit a scaled line is shown in is not tested here.</strong> That rule belongs to
 * {@code Quantities} — this class used to hold a second copy of it, and the copies disagreed about
 * zero — so its vectors live in {@code QuantitiesTest.UnitChoice}, one table run through both
 * callers. A unit-promotion case added here instead of there would prove only that this file agrees
 * with itself, which is what went wrong the first time.
 *
 * <p><strong>The counted vectors were rewritten by T-425 and now assert the opposite of what they
 * used to.</strong> This file's job included pinning that a scaled count stayed fractional — 0.999
 * pieces kept its 0.999 and was merely shown as "1" — which was the clearest statement anywhere in
 * the codebase that the screen and the stock draw were allowed to say different numbers about one
 * coconut. A count is now made whole, upwards, once, at the moment it is produced, and the raw and
 * display figures are the same number. {@code WholeCountedRequirementIT} carries the same rule
 * through the five screens that read it.
 */
class RecipeScalerTest {

	@Test
	@DisplayName("scaling is the linear ratio of target to base, exactly as RM 2019's scaled column")
	void linearRatio() {
		// base 100 -> target 40 is the story's Aam Ras example: ratio 0.4.
		BigDecimal ratio = RecipeScaler.ratio(new BigDecimal("100"), new BigDecimal("40"));
		assertThat(ratio).isEqualByComparingTo("0.4");

		ScaledQuantity mango = RecipeScaler.scale(new BigDecimal("50"), Unit.KG, ratio);
		assertThat(mango.rawQuantity()).isEqualByComparingTo("20");
	}

	@Test
	@DisplayName("display promotes to the larger unit once past 1000 of the smaller (24,000 gm -> 24 Kg)")
	void promotesUpwards() {
		ScaledQuantity q = RecipeScaler.scale(new BigDecimal("60000"), Unit.GM, new BigDecimal("0.4"));
		assertThat(q.rawQuantity()).as("raw stays in the line's unit, unrounded").isEqualByComparingTo("24000");
		assertThat(q.rawUnit()).isEqualTo("GM");
		assertThat(q.displayQuantity()).isEqualByComparingTo("24");
		assertThat(q.displayUnit()).isEqualTo("Kg");
	}

	@Test
	@DisplayName("display demotes to the smaller unit below 1000 (0.4 Kg -> 400 gm)")
	void demotesDownwards() {
		ScaledQuantity q = RecipeScaler.scale(new BigDecimal("2"), Unit.KG, new BigDecimal("0.2"));
		assertThat(q.rawQuantity()).isEqualByComparingTo("0.4");
		assertThat(q.displayQuantity()).isEqualByComparingTo("400");
		assertThat(q.displayUnit()).isEqualTo("gm");
	}

	@Test
	@DisplayName("millilitres promote to litres the same way")
	void volumePromotes() {
		ScaledQuantity q = RecipeScaler.scale(new BigDecimal("2000"), Unit.ML, new BigDecimal("0.75"));
		assertThat(q.displayQuantity()).isEqualByComparingTo("1.5");
		assertThat(q.displayUnit()).isEqualTo("L");
	}

	@Test
	@DisplayName("pieces are counted, never promoted")
	void countIsNotPromoted() {
		ScaledQuantity q = RecipeScaler.scale(new BigDecimal("10"), Unit.PIECES, new BigDecimal("0.4"));
		assertThat(q.displayQuantity()).isEqualByComparingTo("4");
		assertThat(q.displayUnit()).isEqualTo("pieces");
	}

	/**
	 * <strong>The headline of T-425, and the ordering the whole rule depends on.</strong>
	 *
	 * <p>A quarter of a coconut a head is a perfectly ordinary recipe line. Scaled to 800 heads it is
	 * 200 coconuts. It is 800 coconuts if — and only if — the quarter is rounded up to a whole one
	 * before the multiply, which is four times what the temple needs and is the mistake this test
	 * exists to make impossible. The ratio is applied to the line first and the count is made whole
	 * once, last.
	 */
	@Test
	@DisplayName("a quarter of a coconut a head, scaled to 800 heads, is 200 coconuts — not 800")
	void scaleFirstThenRoundOnce() {
		BigDecimal ratio = RecipeScaler.ratio(new BigDecimal("1"), new BigDecimal("800"));

		ScaledQuantity coconut = RecipeScaler.scale(new BigDecimal("0.25"), Unit.PIECES, ratio);

		assertThat(coconut.rawQuantity()).isEqualByComparingTo("200");
		assertThat(coconut.displayQuantity()).isEqualByComparingTo("200");
		assertThat(coconut.displayUnit()).isEqualTo("pieces");
	}

	/**
	 * <strong>A counted requirement is whole and rounded up, and the two fields carry one figure.</strong>
	 *
	 * <p>The application used to hand a fraction to everything downstream — the stock draw, the job
	 * card, sufficiency and the cost estimate all read {@code rawQuantity} — while the screen showed
	 * the rounded one. Banana 16.78 and Coconut 400.98 on the staging stock screen were produced here
	 * and typed by nobody.
	 *
	 * <p>The pair being asserted together is the point. Asserting only that the raw figure is whole
	 * would pass against an implementation that rounded the raw one and left the display one alone,
	 * which is the same disagreement in the other direction.
	 */
	@Test
	@DisplayName("a counted requirement is whole, rounded up, and the same figure in both fields")
	void aCountedRequirementIsWholeAndRoundedUp() {
		// The staging case: 10 bananas for 200 people, cooked for 140. 7 whole bananas, not 7.
		ScaledQuantity bananas = RecipeScaler.scale(new BigDecimal("10"), Unit.PIECES, new BigDecimal("0.67"));
		assertThat(bananas.rawQuantity()).isEqualByComparingTo("7");
		assertThat(bananas.displayQuantity()).isEqualByComparingTo("7");

		// The smallest case there is, and the one HALF_UP got wrong: two fifths of a coconut is still
		// a coconut off the shelf. Rounded to nearest it was nothing at all.
		ScaledQuantity almostNone = RecipeScaler.scale(BigDecimal.ONE, Unit.PIECES, new BigDecimal("0.4"));
		assertThat(almostNone.rawQuantity()).isEqualByComparingTo("1");
		assertThat(almostNone.displayQuantity()).isEqualByComparingTo("1");
		assertThat(almostNone.displayUnit()).isEqualTo("piece");

		// Nothing is still nothing: a line of zero does not become one of something.
		ScaledQuantity none = RecipeScaler.scale(BigDecimal.ZERO, Unit.PIECES, new BigDecimal("500"));
		assertThat(none.rawQuantity()).isEqualByComparingTo("0");
		assertThat(none.displayQuantity()).isEqualByComparingTo("0");
		assertThat(none.displayUnit()).isEqualTo("pieces");
	}

	/**
	 * <strong>The most important negative assertion in the file.</strong>
	 *
	 * <p>The rule is keyed on {@link Unit.Family#COUNT}. A leak into mass or volume would round every
	 * scaled kilo of rice up to a whole one — 2.4 Kg becoming 3 — which is most of what a temple
	 * actually cooks with, and it would do it silently on every screen at once.
	 *
	 * <p>It asserts an absence, so it is deliberately a table rather than one case: a check that the
	 * rounding did not happen only proves something where the figure it is asked about <em>would</em>
	 * have moved under the counted rule. Every vector below is fractional in its own unit after
	 * scaling, so every one of them would fail if the branch above stopped testing the family. Four
	 * vectors, covering both convertible families and both directions of promotion.
	 */
	@Test
	@DisplayName("a fractional Kg, gm, L or ml requirement is left fractional")
	void theRuleDoesNotLeakIntoMassOrVolume() {
		assertThat(RecipeScaler.scale(new BigDecimal("6"), Unit.KG, new BigDecimal("0.4")).rawQuantity())
				.as("2.4 Kg of rice is 2.4 Kg of rice").isEqualByComparingTo("2.4");
		assertThat(RecipeScaler.scale(new BigDecimal("2"), Unit.KG, new BigDecimal("0.2")).rawQuantity())
				.as("and 0.4 Kg is 400 gm, which is a quantity somebody weighs")
				.isEqualByComparingTo("0.4");
		assertThat(RecipeScaler.scale(new BigDecimal("1"), Unit.L, new BigDecimal("0.75")).rawQuantity())
				.as("three quarters of a litre of milk").isEqualByComparingTo("0.75");
		assertThat(RecipeScaler.scale(new BigDecimal("100"), Unit.ML, new BigDecimal("0.125")).rawQuantity())
				.as("12.5 ml of essence").isEqualByComparingTo("12.5");
	}

	/**
	 * "1 pieces" on the recipe scale preview (T-148).
	 *
	 * <p>Not a unit-choice case, so it belongs here rather than in {@code QuantitiesTest.UnitChoice}:
	 * which unit a count is said in never changes, only which <em>word</em> of it agrees with the
	 * figure. The recipe page prints {@code displayQuantity} and {@code displayUnit} as one phrase,
	 * so these two fields are the whole of what the cook reads.
	 */
	@Test
	@DisplayName("a count that scales to exactly one says \"piece\", and two says \"pieces\"")
	void countAgreesWithItsFigure() {
		ScaledQuantity one = RecipeScaler.scale(new BigDecimal("10"), Unit.PIECES, new BigDecimal("0.1"));
		assertThat(one.displayQuantity()).isEqualByComparingTo("1");
		assertThat(one.displayUnit()).isEqualTo("piece");

		ScaledQuantity two = RecipeScaler.scale(new BigDecimal("10"), Unit.PIECES, new BigDecimal("0.2"));
		assertThat(two.displayQuantity()).isEqualByComparingTo("2");
		assertThat(two.displayUnit()).isEqualTo("pieces");

		// A one read back from JDBC wears its column's scale; equals() would call 1.000 not one.
		ScaledQuantity fromDb = RecipeScaler.scale(new BigDecimal("1.000"), Unit.PIECES, BigDecimal.ONE);
		assertThat(fromDb.displayUnit()).isEqualTo("piece");
	}

	/**
	 * <strong>Rewritten by T-425, and the change of intent is the whole of it.</strong>
	 *
	 * <p>These four vectors used to assert that a count <em>stayed</em> fractional: 0.999 pieces kept
	 * its raw 0.999 and was merely shown as "1"; 1.004 was shown as "1" while 1.005 was shown as
	 * "1.01" and therefore read "1.01 pieces". They were the two-decimal-place, round-to-nearest rule
	 * applied to a thing that does not divide, and they were the clearest statement in the codebase
	 * that the screen and the stock draw were allowed to say different numbers.
	 *
	 * <p>The same four figures now assert the opposite. Each is the smallest interesting distance
	 * from a whole coconut, and each takes a whole coconut off the shelf.
	 */
	@Test
	@DisplayName("a count either side of a whole one goes up to the whole one, and the word agrees")
	void aCountEitherSideOfAWholeOneRoundsUp() {
		// 0.999 of a coconut is a coconut, and the raw figure says so now — it used to stay 0.999
		// and be shown as "1", which is the disagreement this task removes.
		ScaledQuantity nearlyOne = RecipeScaler.scale(new BigDecimal("0.999"), Unit.PIECES, BigDecimal.ONE);
		assertThat(nearlyOne.rawQuantity()).isEqualByComparingTo("1");
		assertThat(nearlyOne.displayQuantity()).isEqualByComparingTo("1");
		assertThat(nearlyOne.displayUnit()).isEqualTo("piece");

		// From above, 1.004 needs a second coconut. It used to be shown as "1 piece" and drawn as
		// 1.004, so the cook was told one and the store was charged for a fraction more.
		ScaledQuantity justOver = RecipeScaler.scale(new BigDecimal("1.004"), Unit.PIECES, BigDecimal.ONE);
		assertThat(justOver.rawQuantity()).isEqualByComparingTo("2");
		assertThat(justOver.displayQuantity()).isEqualByComparingTo("2");
		assertThat(justOver.displayUnit()).isEqualTo("pieces");

		// And 1.005, which the old two-place rule showed as "1.01 pieces" — a figure of a thing that
		// cannot be had in hundredths.
		ScaledQuantity overOne = RecipeScaler.scale(new BigDecimal("1.005"), Unit.PIECES, BigDecimal.ONE);
		assertThat(overOne.rawQuantity()).isEqualByComparingTo("2");
		assertThat(overOne.displayQuantity()).isEqualByComparingTo("2");
		assertThat(overOne.displayUnit()).isEqualTo("pieces");

		// Nothing of a count is still nothing, and still plural: "0 pieces". CEILING leaves a zero
		// alone, which is what stops an empty line inventing a coconut.
		ScaledQuantity none = RecipeScaler.scale(BigDecimal.ZERO, Unit.PIECES, new BigDecimal("500"));
		assertThat(none.rawQuantity()).isEqualByComparingTo("0");
		assertThat(none.displayUnit()).isEqualTo("pieces");
	}

	@Test
	@DisplayName("weights and volumes of exactly one are unchanged: \"1 Kg\", \"1 L\"")
	void massAndVolumeHaveNoSingular() {
		assertThat(RecipeScaler.scale(BigDecimal.ONE, Unit.KG, BigDecimal.ONE).displayUnit()).isEqualTo("Kg");
		assertThat(RecipeScaler.scale(BigDecimal.ONE, Unit.L, BigDecimal.ONE).displayUnit()).isEqualTo("L");
		// 1000 gm is promoted to 1 Kg, and is still "Kg".
		ScaledQuantity promoted = RecipeScaler.scale(new BigDecimal("1000"), Unit.GM, BigDecimal.ONE);
		assertThat(promoted.displayQuantity()).isEqualByComparingTo("1");
		assertThat(promoted.displayUnit()).isEqualTo("Kg");
	}

	@Test
	@DisplayName("a 50,000-serving scale computes with no overflow or precision loss")
	void festivalScale() {
		// base 100 -> 50,000 is ratio 500.
		BigDecimal ratio = RecipeScaler.ratio(new BigDecimal("100"), new BigDecimal("50000"));
		assertThat(ratio).isEqualByComparingTo("500");

		ScaledQuantity q = RecipeScaler.scale(new BigDecimal("2.5"), Unit.KG, ratio);
		assertThat(q.rawQuantity()).isEqualByComparingTo("1250");
		assertThat(q.displayQuantity()).isEqualByComparingTo("1250");
		assertThat(q.displayUnit()).isEqualTo("Kg");
	}
}
