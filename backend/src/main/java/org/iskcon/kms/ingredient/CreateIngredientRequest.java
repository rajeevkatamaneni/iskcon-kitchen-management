package org.iskcon.kms.ingredient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A request to add an ingredient to the catalogue. The unit is a string validated in the service
 * against {@link Unit}. Setting {@code sattvicProhibited} true is permitted only to a Temple Admin
 * (MANAGE_SATTVIC_POLICY), checked in the service — for everyone else it must be false.
 */
public record CreateIngredientRequest(

		@NotBlank(message = "Enter the ingredient's name.")
		@Size(max = 200, message = "That name is too long.")
		String name,

		@NotBlank(message = "Choose a category.")
		@Size(max = 100, message = "That category name is too long.")
		String category,

		@NotBlank(message = "Choose a unit.")
		String unit,

		/** Optional; false unless a Temple Admin sets it. */
		boolean sattvicProhibited,

		/** Optional alternate names, matched by typeahead alongside the name. */
		List<@Size(max = 200) String> aliases) {
}
