package org.iskcon.kms.meal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

		/** Where exactly, once the driver is there — "Clubhouse", "Block C, second gate" (V93).
		 * Never geocoded and never routed on: being at the right gate is what matters, and the last
		 * fifty metres is a phone call. Kept apart from the address for that reason. */
		@Size(max = 200) String deliverySubLocation,

		/** Google's stable id for the address the planner picked (V93). Absent when the address was
		 * typed rather than chosen, which is every plan made before the picker existed. */
		@Size(max = 300) String deliveryPlaceId,

		/** Where the picked address actually is. Sent with the place id and never on its own: these
		 * came from our own Places proxy moments earlier, and having them here is what stops the save
		 * throwing away a good pin and asking a geocoder to find the address all over again. */
		@DecimalMin("-90") @DecimalMax("90") BigDecimal deliveryLatitude,
		@DecimalMin("-180") @DecimalMax("180") BigDecimal deliveryLongitude,

		/** How long the temple allows for the drive, in minutes (V93) — Google's estimate, or a
		 * figure from somebody who knows the road better than a traffic model does. */
		@Min(1) @Max(600) Integer travelMinutes,

		/** Whether a person set that figure themselves. It decides whether printing the job card
		 * refreshes the estimate or leaves their correction standing. */
		boolean travelMinutesManual,
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

		/** Anything the people serving this meal need to know (V92) — the mirror of the kitchen's
		 * notes, and what the serving sheet of the job card is for. */
		@Size(max = 2000) String serverNotes,

		/** Set true to knowingly plan an Ekadashi-incompatible recipe on an Ekadashi (E4-S6). */
		boolean ekadashiAcknowledged) {
}
