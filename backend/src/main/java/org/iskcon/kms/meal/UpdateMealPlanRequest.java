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
 * <p>The shape is the same as planning one — see {@link CreateMealPlanRequest} — head count and
 * kitchen notes included, which the first version of this record deliberately left out and which the
 * planner has wanted ever since: the commonest correction is not the recipe at all, it is that forty
 * more people are coming.
 *
 * <p>Once the meal has been recorded this is refused. What was cooked cannot be changed afterwards.
 */
public record UpdateMealPlanRequest(
		@NotNull LocalDate planDate,
		@NotBlank @Size(max = 80) String mealKind,
		@NotNull UUID recipeId,
		@NotNull @Positive BigDecimal targetServings,
		LocalTime readyBy,
		@Size(max = 200) String clientName,
		@Size(max = 200) String clientContact,
		@Size(max = 300) String venue,
		/** What the food is for, where the kind asks (B6). */
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
