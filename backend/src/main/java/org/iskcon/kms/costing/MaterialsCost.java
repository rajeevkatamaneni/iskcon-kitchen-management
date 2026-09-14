package org.iskcon.kms.costing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What a day's food costs, estimated from vendors' last-known prices.
 *
 * <p>The gap is part of the answer, not an error condition. {@code estimatedTotal} covers only the
 * {@code ingredientsPriced} ingredients; {@code ingredientsWithoutPrice} says how many of the day's
 * ingredients it does not cover, and {@code unpriced} names them. A screen that shows the total
 * without the count is reporting a number it cannot stand behind.
 *
 * <p>The same goes for what the figure was worked out from. A recorded meal is costed at what its job
 * card says was cooked and a meal not yet recorded at what was planned (T-212), so the day's figure
 * can be part fact and part intention, and the two counts say how much of each.
 *
 * @param mealsCostedAsCooked  meals in the figure that were recorded, costed at what was cooked
 * @param mealsCostedAsPlanned meals in the figure not yet recorded, costed at what was planned
 */
public record MaterialsCost(
		LocalDate date,
		BigDecimal estimatedTotal,
		int ingredientsPriced,
		int ingredientsWithoutPrice,
		List<UnpricedIngredient> unpriced,
		int mealsCostedAsCooked,
		int mealsCostedAsPlanned) {
}
