package org.iskcon.kms.ingredient.merge;

import static org.assertj.core.api.Assertions.assertThat;

import org.iskcon.kms.ingredient.Unit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The merge's two small rules that need no database: notes, and conversion within a family. */
class IngredientMergeRulesTest {

	@Test
	@DisplayName("a moved preparation goes first, the line's own note after it, and a part already there is not repeated")
	void notesCombine() {
		assertThat(IngredientMergeService.combineNotes("sour", null)).isEqualTo("sour");
		assertThat(IngredientMergeService.combineNotes(null, "chilled")).isEqualTo("chilled");
		assertThat(IngredientMergeService.combineNotes(null, null)).isNull();
		assertThat(IngredientMergeService.combineNotes("sour", "whisked")).isEqualTo("sour, whisked");
		assertThat(IngredientMergeService.combineNotes("sour", "Sour")).isEqualTo("sour");
		assertThat(IngredientMergeService.combineNotes("sour", "whisked, sour")).isEqualTo("sour, whisked");
		assertThat(IngredientMergeService.combineNotes("fresh grated", "for garnish")).isEqualTo("fresh grated, for garnish");
	}

	@Test
	@DisplayName("a price per gm is a thousand times that per Kg; a quantity in gm a thousandth of that in Kg")
	void conversionWithinAFamily() {
		assertThat(IngredientMergeService.priceIn(new java.math.BigDecimal("0.065"), Unit.GM, Unit.KG))
				.isEqualByComparingTo("65");
		assertThat(IngredientMergeService.priceIn(new java.math.BigDecimal("71.20"), Unit.KG, Unit.GM))
				.isEqualByComparingTo("0.0712");
		assertThat(IngredientMergeService.priceIn(new java.math.BigDecimal("40"), Unit.L, Unit.L))
				.isEqualByComparingTo("40");
		assertThat(IngredientMergeService.quantityIn(new java.math.BigDecimal("1500"), Unit.GM, Unit.KG))
				.isEqualByComparingTo("1.5");
		assertThat(IngredientMergeService.quantityIn(new java.math.BigDecimal("2"), Unit.L, Unit.ML))
				.isEqualByComparingTo("2000");
	}
}
