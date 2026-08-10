package org.iskcon.kms.meal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A planned meal as the planner and list views read it (E4-S4). */
public record MealPlanView(
		UUID id,
		LocalDate planDate,
		String slot,
		UUID recipeId,
		String recipeName,
		BigDecimal targetServings,
		DayType dayType,
		String occasionName,
		MealStatus status,
		String clientName,
		String clientContact,
		String venue,
		Instant deliveryTime,
		Instant cookedAt,
		boolean ekadashiAcknowledged,
		Instant createdAt) {
}
