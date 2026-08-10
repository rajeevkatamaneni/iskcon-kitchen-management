package org.iskcon.kms.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.iskcon.kms.ingredient.Unit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The scaling math (E2-S3): linear ratio, unit promotion, and festival-scale precision. */
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
