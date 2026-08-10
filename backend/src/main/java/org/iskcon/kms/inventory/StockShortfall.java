package org.iskcon.kms.inventory;

import java.math.BigDecimal;
import java.util.UUID;

/** One ingredient there isn't enough of to cook a recipe: what's needed against what's on hand. */
public record StockShortfall(
		UUID ingredientId,
		String ingredientName,
		BigDecimal required,
		BigDecimal available,
		String unit) {
}
