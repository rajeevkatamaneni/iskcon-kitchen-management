package org.iskcon.kms.meal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Change a dish that has not been cooked yet (B4) — swap the recipe, or edit the servings, in place.
 *
 * <p>Before this, correcting a dish meant cancelling it and adding another. That leaves a cancelled
 * row that never went anywhere and a new one with no memory of what it replaced, so the day's record
 * reads as two decisions where the kitchen made one. Editing keeps the row and its history.
 *
 * <p>The shape is the same as planning one — see {@link CreateMealPlanRequest} — head count, event
 * fields and kitchen notes included. Editing one copy of a repeated event edits that copy and
 * nothing else (E4-S15 D8): what repeat-forward makes is copies, not a series.
 *
 * <p>Once the meal has been recorded this is refused. What was cooked cannot be changed afterwards.
 */
public record UpdateMealPlanRequest(
		@NotNull LocalDate planDate,
		@NotBlank @Size(max = 80) String mealKind,
		@NotNull UUID recipeId,
		@NotNull @Positive BigDecimal targetYield,
		LocalTime readyBy,

		/** What this event is called (E4-S15). Required by a kind flagged {@code isEvent}. */
		@Size(max = 200) String eventName,
		/** Whether this event's food leaves the temple. */
		boolean isOutside,
		/** PICKUP or DELIVERY, on an event going outside. */
		Handover handover,
		/** Who to ring about food going outside the temple, and their number. */
		@Size(max = 200) String contactName,
		@Size(max = 200) String contactPhone,
		/** Where a delivered event's food is going. */
		@Size(max = 300) String deliveryAddress,
		/** The local time the guests sit down to eat, on a delivery. */
		LocalTime guestsEatAt,

		/** What the food is for, in the planner's own words (B6). */
		@Size(max = 300) String purpose,
		/** Which festival this meal is for, where the kind asks (item 26). */
		@Size(max = 200) String occasionName,

		@PositiveOrZero Integer adults,
		@PositiveOrZero Integer children,
		@PositiveOrZero Integer seniors,
		/** How many people it takes to execute this meal (item 24). One counter, any mix. */
		@Positive Integer crewRequired,
		@Size(max = 2000) String kitchenNotes,

		/** Set true to knowingly plan an Ekadashi-incompatible recipe on an Ekadashi (E4-S6). */
		boolean ekadashiAcknowledged) {
}
