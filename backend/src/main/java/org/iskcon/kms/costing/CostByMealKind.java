package org.iskcon.kms.costing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What each kind of meal costs over a period, and what a serving of it costs (E3-S9).
 *
 * <p>The reviewers' question — what does a public-prasadam plate cost against a Sunday feast plate —
 * is a comparison between categories, and a daily total can never answer it. This is that comparison
 * and nothing more: the same estimate as the Today tile, kept split by kind instead of summed.
 *
 * @param kinds  one row per kind of meal the temple actually cooked in the period, dearest per
 *               serving first, so the comparison reads top to bottom.
 */
public record CostByMealKind(
		LocalDate from,
		LocalDate to,
		int meals,
		int servings,
		int mealsWithoutServings,
		BigDecimal estimatedTotal,
		int ingredientsWithoutPrice,
		List<UnpricedIngredient> unpriced,
		List<MealKindCost> kinds) {
}
