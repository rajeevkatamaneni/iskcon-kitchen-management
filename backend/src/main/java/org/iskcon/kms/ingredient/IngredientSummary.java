package org.iskcon.kms.ingredient;

import java.util.UUID;

/** The lightweight shape a recipe or inventory picker needs from a typeahead search. */
public record IngredientSummary(
		UUID id,
		String name,
		String category,
		String unit,
		/**
		 * Whether this is a supply rather than food (D-1). Carried here as well as on
		 * {@link IngredientView} because this is the shape a <em>recipe</em> picker reads, and a
		 * picker that cannot see the flag cannot honour it. No caller filters on it yet — the
		 * screens all load the whole catalogue through {@code GET /api/v1/ingredients} — so this is
		 * the flag being available where it would be needed rather than a filter being applied.
		 */
		boolean supply) {
}
