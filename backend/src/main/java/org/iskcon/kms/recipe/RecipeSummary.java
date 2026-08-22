package org.iskcon.kms.recipe;

import java.math.BigDecimal;
import java.util.UUID;

/** The list/browse shape (E2-S2, E2-S7): enough to show and filter, not the full ingredient list. */
public record RecipeSummary(
		UUID id,
		String name,
		String categoryName,
		boolean fastingCompatible,
		BigDecimal baseYieldQty,
		String baseYieldUnit,
		String status,
		boolean sattvicOverridden,

		/** What the source said the yield was — "300 idlis (3 per devotee)". Null on a hand-written recipe. */
		String yieldNote,

		/**
		 * What one person eats, in the recipe's own yield unit.
		 *
		 * <p>Carried on the summary because the meal planner needs it for every recipe in its picker
		 * at once: it is what turns a head count into a target — 100 people at 3 idlis is 300 idlis,
		 * not 100. Null where nobody serves the dish by the head, and the planner then asks.
		 */
		java.math.BigDecimal perHeadQty,
		String perHeadUnit,

		/** True where this copy came from the shared library rather than being written here. */
		boolean fromLibrary) {
}
