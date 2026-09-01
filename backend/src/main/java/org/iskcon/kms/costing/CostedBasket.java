package org.iskcon.kms.costing;

import java.math.BigDecimal;
import java.util.List;

/**
 * What an {@link IngredientBasket} is worth, and what the figure does not cover.
 *
 * <p>The gap is part of the answer, not an error condition. {@code estimatedTotal} covers only the
 * {@code ingredientsPriced} ingredients; {@code unpriced} names the rest. Every screen showing the
 * total must show the count beside it — a figure that quietly omits a third of the basket is worse
 * than one that admits the hole, because only the second can be acted on (E3-S8 D2).
 */
public record CostedBasket(
		BigDecimal estimatedTotal,
		int ingredientsPriced,
		List<UnpricedIngredient> unpriced) {

	public int ingredientsWithoutPrice() {
		return unpriced.size();
	}
}
