package org.iskcon.kms.ingredient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The full view of an ingredient, for the catalogue list and detail. */
public record IngredientView(
		UUID id,
		String name,
		String category,
		String unit,
		boolean sattvicProhibited,
		List<String> aliases,
		Instant createdAt) {
}
