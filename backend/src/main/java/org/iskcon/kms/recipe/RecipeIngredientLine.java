package org.iskcon.kms.recipe;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One ingredient line of a recipe, as submitted. The unit is validated against {@link org.iskcon.kms.ingredient.Unit}.
 *
 * <p>{@code preparationNote} is how the cook prepares the ingredient for this recipe — "halved",
 * "slit", "fresh grated" (R-DUP-1). It lives on the line and not in the ingredient's name, because a
 * name is what stock, prices and the shopping list are kept against, and "Cashew, halved" as an
 * ingredient of its own split the temple's cashew in two. Optional: blank or absent is saved as null,
 * which is what the database's not-blank check expects (V144).
 */
public record RecipeIngredientLine(

		@NotNull(message = "Choose an ingredient.")
		UUID ingredientId,

		@NotNull(message = "Enter a quantity.")
		@DecimalMin(value = "0.0", inclusive = false, message = "Quantity must be greater than zero.")
		BigDecimal quantity,

		@NotBlank(message = "Choose a unit.")
		String unit,

		@Size(max = 200, message = "That preparation note is too long.")
		String preparationNote) {

	/**
	 * A line with no preparation note. Kept so that a caller with nothing to say about how the
	 * ingredient is prepared does not have to pass a null to say so.
	 */
	public RecipeIngredientLine(UUID ingredientId, BigDecimal quantity, String unit) {
		this(ingredientId, quantity, unit, null);
	}

	/** The note as it is stored: trimmed, and null when there is nothing in it. */
	public String storedPreparationNote() {
		return preparationNote == null || preparationNote.isBlank() ? null : preparationNote.strip();
	}
}
