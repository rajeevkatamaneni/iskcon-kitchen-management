package org.iskcon.kms.ingredientrequest;

import java.math.BigDecimal;
import java.util.UUID;

/** One dish the kitchen said it was cooking. It travels with the request everywhere it is read. */
public record IngredientRequestDishView(
		UUID id,
		int lineNo,
		String dishName,
		BigDecimal quantity,
		String unit) {
}
