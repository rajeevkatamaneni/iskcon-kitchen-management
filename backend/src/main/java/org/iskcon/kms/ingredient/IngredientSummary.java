package org.iskcon.kms.ingredient;

import java.util.UUID;

/** The lightweight shape a recipe or inventory picker needs from a typeahead search. */
public record IngredientSummary(
		UUID id,
		String name,
		String category,
		String unit,
		boolean sattvicProhibited) {
}
