package org.iskcon.kms.meal;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A planned meal's ingredient sufficiency (E4-S5), computed across the horizon so two meals can't
 * both claim the same stock. {@code shortfalls} lists only the ingredients that fall short.
 */
public record MealSufficiency(
		UUID mealPlanId,
		LocalDate planDate,
		String slot,
		String recipeName,
		SufficiencyStatus status,
		List<IngredientShortfall> shortfalls) {
}
