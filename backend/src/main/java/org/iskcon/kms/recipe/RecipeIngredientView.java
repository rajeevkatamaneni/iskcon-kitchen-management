package org.iskcon.kms.recipe;

import java.math.BigDecimal;
import java.util.UUID;

/** One rendered ingredient line: the ingredient's identity, how much, and whether it's prohibited. */
public record RecipeIngredientView(
		UUID ingredientId,
		String ingredientName,
		BigDecimal quantity,
		String unit,
		boolean sattvicProhibited) {
}
