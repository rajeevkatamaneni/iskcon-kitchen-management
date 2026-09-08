package org.iskcon.kms.recipe;

import java.math.BigDecimal;
import java.util.UUID;

/** One rendered ingredient line: the ingredient's identity and how much of it. */
public record RecipeIngredientView(
		UUID ingredientId,
		String ingredientName,
		BigDecimal quantity,
		String unit) {
}
