package org.iskcon.kms.meal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * A planned meal as the planner reads it (E4-S7).
 *
 * @param mealKind what is being cooked for — Breakfast, Deity Offering, Event…
 * @param eventName what this event is called (E4-S15), where the kind is an event. It is what the
 *                 day shows for the meal: the Saturday reading appears under its own name rather
 *                 than as *Event* with no further identity.
 * @param isOutside this food leaves the temple. What *Upcoming outside commitments* is keyed off.
 * @param handover PICKUP or DELIVERY on an event going outside; null on an in-house one, and null
 *                 on the outside plans V88 carried across, which predate the question.
 * @param guestsEatAt the local time the guests sit down to eat, on a delivery. E4-S16 works
 *                 backwards from it to say when to leave the temple.
 * @param readyBy  the local time the food must be ready; every meal has one.
 * @param dayType  derived from the date and the calendar, never chosen by a person. Kept because a
 *                 festival day still explains a large serving count long after the fact.
 * @param purpose  what the food is for, in the planner's own words (B6). Free text; nothing
 *                 computes on it. No kind demands it any more, but it is still printed on the card.
 * @param crewRequired how many people it takes to execute this meal, any mix of staff and volunteers
 *                 (item 24). A whole-meal fact carried on each dish row, like the head count and the
 *                 ready-by. Null where nobody has said, and null is the honest answer — a made-up
 *                 number would not be.
 * @param actualServings how much of this dish was actually cooked, from the returned job card (B5),
 *                 in the recipe's own yield unit. Null until the meal is recorded — and never a
 *                 substitute for {@code targetYield}, because the gap between the two is the thing
 *                 worth having.
 * @param consumedQuantity how much of what was cooked actually went out. Null where the card did
 *                 not say; what is left over is the difference, and that difference is why a
 *                 temple records anything.
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
		BigDecimal targetYield,

		/**
		 * What {@code targetYield} is measured in — the recipe's own yield unit, carried here so a
		 * screen showing a dish does not have to hold the whole recipe list to say what its number
		 * means. The Today screen had no such list and printed the figure bare (E11-S4).
		 */
		String targetYieldUnit,
		DayType dayType,
		String occasionName,
		MealStatus status,
		String eventName,
		boolean isOutside,
		Handover handover,
		String contactName,
		String contactPhone,
		String deliveryAddress,
		LocalTime guestsEatAt,
		String purpose,
		Integer adults,
		Integer children,
		Integer seniors,
		Integer crewRequired,
		String kitchenNotes,
		BigDecimal actualServings,
		BigDecimal consumedQuantity,
		boolean notMade,
		Instant cookedAt,
		boolean ekadashiAcknowledged,
		Instant createdAt) {
}
