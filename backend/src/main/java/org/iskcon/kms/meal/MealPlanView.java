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
 * @param purpose  what an outside event's food is for (B6). Free text; nothing computes on it.
 * @param crewRequired how many people it takes to execute this meal, any mix of staff and volunteers
 *                 (item 24). A whole-meal fact carried on each dish row, like the head count and the
 *                 ready-by. Null where nobody has said, and null is the honest answer — a made-up
 *                 number would not be.
 * @param actualServings what this dish actually went out at, from the returned job card (B5). Null
 *                 until the meal is recorded — and never a substitute for {@code targetServings},
 *                 because the gap between the two is the thing worth having.
 * @param notMade  the dish never went into a pot. Its row reads CANCELLED, and this says the meal
 *                 was called off at the stove rather than in the plan.
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
		String purpose,
		Integer adults,
		Integer children,
		Integer seniors,
		Integer crewRequired,
		String kitchenNotes,
		BigDecimal actualServings,
		boolean notMade,
		Instant cookedAt,
		boolean ekadashiAcknowledged,
		Instant createdAt) {
}
