package org.iskcon.kms.meal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * A planned meal as the planner reads it (E4-S7).
 *
 * @param mealKind what is being cooked for — Breakfast, Deity offering, Catering order…
 * @param readyBy  the local time the food must be ready; every meal has one.
 * @param dayType  derived from the date and the calendar, never chosen by a person. Kept because a
 *                 festival day still explains a large serving count long after the fact.
 */
public record MealPlanView(
		UUID id,
		LocalDate planDate,
		String mealKind,
		LocalTime readyBy,
		UUID recipeId,
		String recipeName,
		BigDecimal targetServings,
		DayType dayType,
		String occasionName,
		MealStatus status,
		String clientName,
		String clientContact,
		String venue,
		Instant cookedAt,
		boolean ekadashiAcknowledged,
		Instant createdAt) {
}
