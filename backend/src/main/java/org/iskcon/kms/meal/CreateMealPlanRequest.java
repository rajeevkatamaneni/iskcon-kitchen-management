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
 * Plan a meal (E4-S7).
 *
 * <p>Note what is absent: the day type. Whether a day is a weekend, a festival or an ordinary
 * Tuesday follows from the date and the calendar, so nobody is asked — the server derives and
 * records it. What the planner supplies is what they actually know: what is being cooked, how much,
 * and when it must be ready.
 *
 * <p>{@code readyBy} may be omitted only for a kind that carries a default time; for the occasional
 * kinds it is required, which is the whole point of them having no default.
 */
public record CreateMealPlanRequest(
		@NotNull LocalDate planDate,
		@NotBlank @Size(max = 80) String mealKind,
		@NotNull UUID recipeId,
		@NotNull @Positive BigDecimal targetServings,
		LocalTime readyBy,
		@Size(max = 200) String clientName,
		@Size(max = 200) String clientContact,
		@Size(max = 300) String venue,

		/** What the food is for, where the kind asks (B6) — a reading, book distribution, a school
		 * event. Free text and not a picklist: the reasons are open-ended, and this is a label for
		 * the kitchen and the job card, not something the system reasons about. */
		@Size(max = 300) String purpose,

		/** The hall as the planner expects it. Optional: a meal may still be given a flat servings
		 * figure, and every meal planned before this existed has one and no breakdown. */
		@PositiveOrZero Integer adults,
		@PositiveOrZero Integer children,
		@PositiveOrZero Integer seniors,

		/** What the cooks should know about this meal, in the planner's own words. */
		@Size(max = 2000) String kitchenNotes,
		/** Set true to knowingly plan an Ekadashi-incompatible recipe on an Ekadashi (E4-S6). */
		boolean ekadashiAcknowledged) {
}
