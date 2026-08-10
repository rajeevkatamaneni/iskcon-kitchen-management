package org.iskcon.kms.recipe;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A request to edit a recipe. Full replacement of the editable fields and the ingredient lines. */
public record UpdateRecipeRequest(

		@NotBlank(message = "Enter the recipe's name.")
		@Size(max = 300, message = "That name is too long.")
		String name,

		@NotNull(message = "Choose a category.")
		UUID categoryId,

		@NotNull(message = "Enter the base yield.")
		@DecimalMin(value = "0.0", inclusive = false, message = "Yield must be greater than zero.")
		BigDecimal baseYieldQty,

		@NotBlank(message = "Choose a yield unit.")
		String baseYieldUnit,

		String method,
		String notes,
		String regionTag,

		@NotEmpty(message = "A recipe needs at least one ingredient.")
		@Valid
		List<RecipeIngredientLine> ingredients,

		String sattvicOverrideReason) {
}
