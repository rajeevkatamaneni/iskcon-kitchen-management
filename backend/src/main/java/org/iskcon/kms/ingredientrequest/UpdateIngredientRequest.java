package org.iskcon.kms.ingredientrequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Editing a request that has not been answered yet.
 *
 * <p>A full replacement, lines and dishes included, as every other edit in this codebase is: a
 * partial patch cannot tell "the caller left this out" from "the caller removed this", and removing
 * a line somebody added by mistake is the commonest edit there is. The lines are rewritten from
 * scratch on save, so their ids are not part of this shape.
 */
public record UpdateIngredientRequest(

		@NotNull(message = "Choose which kitchen this is for.")
		UUID kitchenId,

		@NotNull(message = "Say when the kitchen needs it.")
		LocalDate neededOn,

		@Size(max = 2000, message = "That reason is too long.")
		String purpose,

		@Valid List<IngredientRequestLineInput> lines,

		@Valid List<IngredientRequestDishInput> dishes) {
}
