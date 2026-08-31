package org.iskcon.kms.ingredientrequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Raising a request for ingredients (E10-S5).
 *
 * <p>Everything but the kitchen and the date may be empty here. A draft is allowed to be rough —
 * somebody writing down what a festival needs does it over a morning, not in one sitting — and the
 * discipline arrives at submission, where a request with no ingredients or no dishes is refused.
 * Enforcing it at creation would only teach people to keep their notes somewhere else.
 */
public record CreateIngredientRequest(

		@NotNull(message = "Choose which kitchen this is for.")
		UUID kitchenId,

		@NotNull(message = "Say when the kitchen needs it.")
		LocalDate neededOn,

		/** Why: "Janmashtami feast", "Sunday lunch for 400". Free text, and read by the approver. */
		@Size(max = 2000, message = "That reason is too long.")
		String purpose,

		@Valid List<IngredientRequestLineInput> lines,

		@Valid List<IngredientRequestDishInput> dishes) {
}
