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
 * loaded batch nobody typed — 44 recipes generated from Rajeev's curated files today, 5,376 vendored
 * ones when it was written.
 */
public record MasterRecipeInput(

		@NotBlank(message = "Enter the recipe's name.")
		@Size(max = 300, message = "That name is too long.") String name,

		@Size(max = 300, message = "That subtitle is too long.") String subtitle,

		@NotBlank(message = "Say which state this is from.")
		@Size(max = 100, message = "That state name is too long.") String state,

		@NotBlank(message = "Enter the state's short name.")
		@Size(max = 100, message = "That short name is too long.")
		String stateSlug,
		@Size(max = 100, message = "That language name is too long.") String bookLanguage,

		@NotBlank(message = "Enter the recipe's short name.")
		@Size(max = 200, message = "That short name is too long.")
		String recipeSlug,

		@NotBlank(message = "Enter the category's short name.")
		@Size(max = 100, message = "That short name is too long.")
		String categoryKey,
		@NotBlank(message = "Enter the category's name.")
		@Size(max = 200, message = "That category name is too long.")
		String categoryName,

		@NotBlank(message = "Choose how often this is cooked.") String badge,

		@NotBlank(message = "Say what it makes.") @Size(max = 200, message = "That description of what it makes is too long.") String yieldText,

		@NotNull(message = "Enter the yield.")
		@DecimalMin(value = "0.0", inclusive = false, message = "The yield must be more than zero.")
		BigDecimal yieldQty,

		@NotBlank(message = "Choose a yield unit.") String yieldUnit,

		@Size(max = 100, message = "That per-head amount is too long.") String perHeadText,
		BigDecimal perHeadQty,
		String perHeadUnit,

		BigDecimal indicativeCost,
		@Size(max = 200, message = "That region name is too long.") String region,

		@NotBlank(message = "Say why a temple would cook this.") String why,
		String cateringNote,
		String noteStart,
		String noteVessel,
		String noteSeason,

		List<@Size(max = 100, message = "That tag is too long.") String> tags,
		List<@Size(max = 200, message = "That serving suggestion is too long.") String> serveWith,

		@NotEmpty(message = "A recipe needs at least one ingredient.")
		@Valid List<Line> ingredients,

		@NotEmpty(message = "A recipe needs at least one step.")
		List<String> method) {

	/**
	 * One ingredient line.
	 *
	 * @param qty  as a person writes it — "8 L", "200 gm". Parsed on the way in, and refused if it
	 *             does not resolve: a quantity nobody can compute with is worse than no recipe.
	 * @param prep what the cook does to it — "Slit", "Roasted" — or nothing. Optional, because most
	 *             lines have none and a book that never wrote one should not force a person to
	 *             invent one. Capped at 200 with the same message the merge tool uses for the note
	 *             it writes onto the very same recipe lines ({@code MergeGroupInput.Member}), so one
	 *             field does not accept what the other refuses. The column it eventually lands in,
	 *             {@code recipe_ingredients.preparation_note}, is TEXT and has no length of its own
	 *             — only a check that it is not blank, which is why a blank is stored as null here.
	 * @param notBought whether the temple never buys this at all — water (T-403). A primitive, so
	 *             leaving it out means false, and false means the temple buys it. That is the right
	 *             default for a field being added to an existing body, and it is also the one field
	 *             here a caller can erase by omission: a tool that reads a curated recipe and writes
	 *             it back without this key turns water back into something bought. Nothing in the
	 *             frontend posts a library recipe, so there is no screen to get this wrong; a GET
	 *             returns it on every line and a faithful round-trip sends it back.
	 */
	public record Line(
			@NotBlank(message = "Enter the ingredient's name.")
			@Size(max = 300, message = "That name is too long.")
			String name,
			@NotBlank(message = "Enter how much of it is needed.")
			@Size(max = 50, message = "That amount is too long.")
			String qty,
			@Size(max = 200, message = "That preparation note is too long.")
			String prep,
			boolean notBought) {
	}
}
