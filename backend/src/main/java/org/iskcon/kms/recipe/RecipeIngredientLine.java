package org.iskcon.kms.recipe;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/** One ingredient line of a recipe, as submitted. The unit is validated against {@link org.iskcon.kms.ingredient.Unit}. */
public record RecipeIngredientLine(

		@NotNull(message = "Choose an ingredient.")
		UUID ingredientId,

		@NotNull(message = "Enter a quantity.")
		@DecimalMin(value = "0.0", inclusive = false, message = "Quantity must be greater than zero.")
		BigDecimal quantity,

		@NotBlank(message = "Choose a unit.")
		String unit) {
}
