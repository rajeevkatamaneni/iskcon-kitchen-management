package org.iskcon.kms.meal;

import java.math.BigDecimal;
import java.util.UUID;

/** One ingredient a meal is short of: what it needs, what's left for it, and the gap (E4-S5). */
public record IngredientShortfall(
		UUID ingredientId,
		String ingredientName,
		BigDecimal required,
		BigDecimal available,
		BigDecimal shortBy,
		String unit) {
}
