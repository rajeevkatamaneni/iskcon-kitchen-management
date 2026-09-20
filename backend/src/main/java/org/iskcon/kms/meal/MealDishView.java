package org.iskcon.kms.meal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One dish of one meal, as the planner and the recording form read it (D-27).
 *
 * <p><strong>Only what belongs to the dish.</strong> Until D-27 this record was {@code MealPlanView}
 * and carried every whole-meal fact — the date, the kind, the head count, the event's address —
 * because those were copied onto every dish row and a meal existed only as a group of rows sharing
 * a date, a kind and an event name. Rajeev ruled that out: <em>"identifying things by text is a
 * terrible idea and one that WILL fail eventually."</em> A meal is a row of its own now, the dish
 * points at it by {@code mealId}, and every whole-meal fact is read from {@link ServedMeal}, once.
 * A dish that still repeated them would be a second place for the head count to disagree.
 *
 * @param id               the dish's own id. Unchanged from the {@code meal_plans} row it was,
 *                         because stock movements with reference {@code MEAL_PLAN} point at it
 *                         without a foreign key (V136's header).
 * @param mealId           the meal this dish is part of.
 * @param kitchenId        the kitchen cooking this dish (Epic 12) — always one of its meal's
 *                         {@link ServedMeal#kitchens()}; the database's composite key refuses any other.
 * @param targetYieldUnit  what {@code targetYield} is measured in: the recipe's own yield unit,
 *                         carried here so a screen showing a dish does not need the recipe list to
 *                         say what its number means (E11-S4).
 * @param actualServings   how much of this dish was actually cooked, from the returned job card
 *                         (B5), in the recipe's yield unit. Null until the meal is recorded.
 * @param consumedQuantity how much of what was cooked went out. Null where the card did not say.
 * @param notMade          the dish never went into a pot. Its status reads CANCELLED, and this
 *                         says it was called off at the stove rather than in the plan.
 * @param originalActualServings   what this dish was first recorded at, before a correction
 *                         replaced {@code actualServings} (T-007). Null where nothing was corrected.
 * @param originalConsumedQuantity the consumed figure it was first recorded at.
 */
public record MealDishView(
		UUID id,
		UUID mealId,
		UUID kitchenId,
		UUID recipeId,
		String recipeName,
		BigDecimal targetYield,
		String targetYieldUnit,
		MealStatus status,
		BigDecimal actualServings,
		BigDecimal consumedQuantity,
		boolean notMade,
		BigDecimal originalActualServings,
		BigDecimal originalConsumedQuantity,
		Instant cookedAt,
		boolean ekadashiAcknowledged,
		Instant createdAt) {
}
