package org.iskcon.kms.costing;

import java.math.BigDecimal;
import java.util.List;

/**
 * One kind of meal over the reported period, and what a serving of it costs (E3-S9).
 *
 * <p>The kind is whatever this temple calls it. There is no fixed list: {@code meal_kinds} is tenant
 * data, and a temple that cooks a Festival feast, an Annadana or a hostel dinner sees exactly those
 * rows. Kinds with no meals in the period do not appear at all — a row of dashes is not a finding.
 *
 * @param meals                 how many meals of this kind the period holds. A meal is a date and a
 *                              kind, so a lunch of three dishes counts once.
 * @param servings              the head count across the meals that recorded one. Never a sum of
 *                              dishes: a lunch of three dishes for 250 people fed 250 people.
 * @param mealsWithoutServings  how many of those meals nobody gave a head count. They are in
 *                              {@code meals} and in {@code estimatedTotal}, and out of
 *                              {@code costPerServing} — see {@link MealKindCostService}.
 * @param costPerServing        the estimate for the meals that have a head count, divided by that
 *                              head count. Null where no meal of this kind has one, because a
 *                              figure divided by nothing is worse than no figure.
 * @param ingredientsWithoutPrice how many of this kind's ingredients the estimate does not cover.
 *                              Shown wherever the total is shown (E3-S8 D2); {@code unpriced} names
 *                              them.
 */
public record MealKindCost(
		String mealKind,
		int meals,
		int servings,
		int mealsWithoutServings,
		BigDecimal estimatedTotal,
		BigDecimal costPerServing,
		int ingredientsPriced,
		int ingredientsWithoutPrice,
		List<UnpricedIngredient> unpriced) {
}
