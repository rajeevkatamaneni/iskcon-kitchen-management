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
	 * @param scaled   the book's own arithmetic at 50, 100, 250 and 500 devotees, where it did any.
	 *                 Present on 19,356 of the 46,337 lines; null on the rest.
	 */
	public record MasterRecipeIngredient(
			String name,
			String qty,
			BigDecimal qtyValue,
			String qtyUnit,
			java.util.Map<String, String> scaled) {
	}
}
