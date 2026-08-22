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

/**
 * A request to create a recipe. At least one ingredient line and a positive base yield are
 * required; the category and units are validated in the service against the tenant's own data and
 * the fixed vocabularies. A prohibited ingredient is refused here unless overridden (E2-S4).
 */
public record CreateRecipeRequest(

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

		/** What the source said the yield was, verbatim — "300 idlis (3 per devotee)". */
		@Size(max = 200) String yieldNote,

		/**
		 * What one person eats, in the recipe's own yield unit. Optional, and the planner asks where
		 * it is absent rather than assuming a head count is a quantity.
		 */
		@DecimalMin(value = "0.0", inclusive = false, message = "A portion must be more than zero.")
		BigDecimal perHeadQty,
		String perHeadUnit,

		@Size(max = 300) String subtitle,
		String badge,
		BigDecimal indicativeCost,
		String why,
		String cateringNote,
		@Size(max = 200) String subRegion,
		String noteStart,
		String noteVessel,
		String noteSeason,
		List<@Size(max = 100) String> tags,
		List<@Size(max = 200) String> serveWith,


		@NotEmpty(message = "A recipe needs at least one ingredient.")
		@Valid
		List<RecipeIngredientLine> ingredients,

		/** A Temple Admin's reason for saving despite a prohibited ingredient (E2-S4). Null otherwise. */
		String sattvicOverrideReason) {
}
