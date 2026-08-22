package org.iskcon.kms.library;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * A library recipe as an operator writes it (E2-S15).
 *
 * <p>The same shape the loader produces, minus the parts only a book has: there is no
 * {@code display_name} here because an operator naming a recipe by hand names it once and means it,
 * and no disambiguation rung for the same reason. The ladder exists to settle collisions inside a
 * batch of 5,376 nobody typed.
 */
public record MasterRecipeInput(

		@NotBlank(message = "Enter the recipe's name.")
		@Size(max = 300) String name,

		@Size(max = 300) String subtitle,

		@NotBlank(message = "Say which state this is from.")
		@Size(max = 100) String state,

		@NotBlank @Size(max = 100) String stateSlug,
		@Size(max = 100) String bookLanguage,

		@NotBlank @Size(max = 200) String recipeSlug,

		@NotBlank @Size(max = 100) String categoryKey,
		@NotBlank @Size(max = 200) String categoryName,

		@NotBlank(message = "Choose how often this is cooked.") String badge,

		@NotBlank(message = "Say what it makes.") @Size(max = 200) String yieldText,

		@NotNull(message = "Enter the yield.")
		@DecimalMin(value = "0.0", inclusive = false, message = "The yield must be more than zero.")
		BigDecimal yieldQty,

		@NotBlank String yieldUnit,

		@Size(max = 100) String perHeadText,
		BigDecimal perHeadQty,
		String perHeadUnit,

		BigDecimal indicativeCost,
		@Size(max = 200) String region,

		@NotBlank(message = "Say why a temple would cook this.") String why,
		String cateringNote,
		String noteStart,
		String noteVessel,
		String noteSeason,

		List<@Size(max = 100) String> tags,
		List<@Size(max = 200) String> serveWith,

		@NotEmpty(message = "A recipe needs at least one ingredient.")
		@Valid List<Line> ingredients,

		@NotEmpty(message = "A recipe needs at least one step.")
		List<String> method) {

	/**
	 * One ingredient line.
	 *
	 * @param qty as a person writes it — "8 L", "200 gm". Parsed on the way in, and refused if it
	 *            does not resolve: a quantity nobody can compute with is worse than no recipe.
	 */
	public record Line(
			@NotBlank @Size(max = 300) String name,
			@NotBlank @Size(max = 50) String qty) {
	}
}
