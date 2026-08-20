package org.iskcon.kms.costing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What a day's planned food costs, estimated from vendors' last-known prices.
 *
 * <p>The gap is part of the answer, not an error condition. {@code estimatedTotal} covers only the
 * {@code ingredientsPriced} ingredients; {@code ingredientsWithoutPrice} says how many of the day's
 * ingredients it does not cover, and {@code unpriced} names them. A screen that shows the total
 * without the count is reporting a number it cannot stand behind.
 */
public record MaterialsCost(
		LocalDate date,
		BigDecimal estimatedTotal,
		int ingredientsPriced,
		int ingredientsWithoutPrice,
		List<UnpricedIngredient> unpriced) {
}
