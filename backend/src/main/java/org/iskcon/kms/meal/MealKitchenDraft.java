package org.iskcon.kms.meal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

/**
 * One kitchen's section of a meal being saved (Epic 12, T-354): which kitchen, and how many people it
 * needs for this meal.
 *
 * <p><strong>Zero is "not said", not a refusal.</strong> The composer's People needed box is empty
 * until somebody knows, and an empty box that a browser serialises as 0 must not stop a save. So 0 is
 * accepted here and stored as null, which is what {@code meal_kitchens.crew_required} has always meant
 * by "nobody has said" (V67, V150); the column's CHECK refuses a stored 0 behind this. A negative
 * figure is refused as a typing slip.
 *
 * @param kitchenId    the kitchen cooking. Required from a client. Null only from the legacy
 *                     {@link SaveMealRequest} constructor, where it means "the saver's default planning
 *                     kitchen" — the one section an old-shaped save always had (see there).
 * @param crewRequired this kitchen's People needed; null or 0 for "nobody has said".
 */
public record MealKitchenDraft(
		@NotNull(message = "Choose the kitchen cooking this part of the meal.") UUID kitchenId,
		@PositiveOrZero(message = "People needed cannot be less than nothing.") Integer crewRequired) {

	/** The figure as it is stored: null for "not said", which a 0 from an empty box also means. */
	Integer storedCrew() {
		return crewRequired == null || crewRequired == 0 ? null : crewRequired;
	}
}
