package org.iskcon.kms.meal;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.vendor.OrderUrgency;

/**
 * A planned meal's ingredient sufficiency (E4-S5), computed across the horizon so two meals can't
 * both claim the same stock. {@code shortfalls} lists only the ingredients that fall short.
 *
 * @param orderBy the last day this meal's shortage could be ordered and still arrive — the meal's
 *     own date minus the lead time (T-090). <strong>Null unless {@code status} is {@code SHORT}</strong>,
 *     because a meal that is covered has nothing to order and a meal outside the buying window is
 *     making no claim about stock at all. Where several ingredients are short it is the
 *     <em>earliest</em> of their order-by dates: the badge has one date to show and the one that
 *     matters is the one that runs out first.
 * @param orderUrgency where today stands against that date — amber while there is slack, red on the
 *     day, and a plain statement of fact past it. Null exactly when {@code orderBy} is.
 */
public record MealSufficiency(
		UUID mealPlanId,
		LocalDate planDate,
		String mealKind,
		java.time.LocalTime readyBy,
		String recipeName,
		SufficiencyStatus status,
		List<IngredientShortfall> shortfalls,
		LocalDate orderBy,
		OrderUrgency orderUrgency) {
}
