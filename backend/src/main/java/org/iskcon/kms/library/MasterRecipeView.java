package org.iskcon.kms.library;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A library recipe in full, for the reading screen and for the operator's editor. */
public record MasterRecipeView(
		UUID id,
		String name,
		String displayName,
		String subtitle,
		String categoryKey,
		String categoryName,
		String state,
		String region,
		String badge,

		/** What the book said, verbatim — "300 idlis (3 per devotee)". */
		String yieldText,
		BigDecimal yieldQty,
		String yieldUnit,

		String perHeadText,
		BigDecimal perHeadQty,
		String perHeadUnit,

		BigDecimal indicativeCost,
		String why,
		String cateringNote,
		String noteStart,
		String noteVessel,
		String noteSeason,
		List<String> tags,
		List<String> serveWith,
		List<MasterRecipeIngredient> ingredients,
		List<String> method,

		/** Where the row came from, down to the commit. Shown to an operator, not to a temple. */
		String sourceRef,

		/** True where this temple already holds a copy, or a recipe of the same name. */
		boolean alreadyAdded) {

	/**
	 * One ingredient line.
	 *
	 * @param qty      as the book wrote it — "8 L", "200 gm"
	 * @param qtyValue the same, parsed, in {@code qtyUnit}
	 * @param prep     what the cook does to this line — "Slit", "Roasted", "Soaked overnight" — as
	 *                 the book wrote it, or null where it says nothing. The books vendored so far
	 *                 keep it inside the name after a comma ("Green chilli, slit") and this is null
	 *                 for every one of their lines; Rajeev's curated recipes (2026-09-19) name the
	 *                 ingredient plainly and fill this instead, which is the only record of those 84
	 *                 notes. The import prefers it over splitting the name — see
	 *                 {@code RecipeImportService.plan} — because a curated name has no comma left to
	 *                 split and the note would otherwise come out empty.
	 * @param notBought true where the book says the temple never buys this — water, and in Rajeev's
	 *                 curated set (2026-09-19) nothing else: fifteen lines, all of them water. Unlike
	 *                 every other field here it describes the <em>ingredient</em> rather than this
	 *                 line of this recipe, which is why the import puts it on the ingredient row
	 *                 ({@code ingredients.is_not_bought}, V153) and not on the recipe line. A marked
	 *                 ingredient is cooked with, drawn from stock and costed exactly as any other;
	 *                 the single thing it never does is appear on a shopping list. Stored in the
	 *                 book's own spelling, {@code not_bought}, inside {@code master_recipes.
	 *                 ingredients}; a primitive, so a line that says nothing is false — "the temple
	 *                 buys this", which is the answer for 445 of the 460 curated lines and every one
	 *                 of the 46,337 vendored ones.
	 * @param scaled   the book's own arithmetic at 50, 100, 250 and 500 devotees, where it did any.
	 *                 Present on 19,356 of the 46,337 lines; null on the rest.
	 */
	public record MasterRecipeIngredient(
			String name,
			String qty,
			BigDecimal qtyValue,
			String qtyUnit,
			String prep,
			boolean notBought,
			java.util.Map<String, String> scaled) {
	}
}
