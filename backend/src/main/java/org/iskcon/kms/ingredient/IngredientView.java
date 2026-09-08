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
		boolean ekadashiProhibited,
		/**
		 * A consumable supply rather than food — LPG, leaf plates, dishwashing liquid, hand soap,
		 * first aid (D-1). It is sent on every ingredient, food or not, because the recipe picker
		 * on the client decides what to offer from this field alone; an absent key would deserialise
		 * to the permissive answer there exactly as it does here.
		 */
		boolean supply,
		List<String> aliases,
		Instant createdAt) {
}
