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

	@Test
	@DisplayName("the word agrees with the ROUNDED figure the page prints, not the raw one")
	void countAgreesWithTheRoundedFigure() {
		// 0.999 is shown "1" (two places, half up), so it must read "1 piece". The raw value is
		// untouched, and is not one.
		ScaledQuantity nearlyOne = RecipeScaler.scale(new BigDecimal("0.999"), Unit.PIECES, BigDecimal.ONE);
		assertThat(nearlyOne.rawQuantity()).isEqualByComparingTo("0.999");
		assertThat(nearlyOne.displayQuantity()).isEqualByComparingTo("1");
		assertThat(nearlyOne.displayUnit()).isEqualTo("piece");

		// And from above: 1.004 is shown "1" too.
		ScaledQuantity justOver = RecipeScaler.scale(new BigDecimal("1.004"), Unit.PIECES, BigDecimal.ONE);
		assertThat(justOver.displayQuantity()).isEqualByComparingTo("1");
		assertThat(justOver.displayUnit()).isEqualTo("piece");

		// 1.005 rounds to 1.01, which is not one, so it stays plural.
		ScaledQuantity overOne = RecipeScaler.scale(new BigDecimal("1.005"), Unit.PIECES, BigDecimal.ONE);
		assertThat(overOne.displayQuantity()).isEqualByComparingTo("1.01");
		assertThat(overOne.displayUnit()).isEqualTo("pieces");

		// Nothing of a count is still plural: "0 pieces".
		ScaledQuantity none = RecipeScaler.scale(BigDecimal.ZERO, Unit.PIECES, new BigDecimal("500"));
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
