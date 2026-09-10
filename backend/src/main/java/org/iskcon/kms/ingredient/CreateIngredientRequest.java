package org.iskcon.kms.ingredient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A request to add an ingredient to the catalogue. The unit is a string validated in the service
 * against {@link Unit}. Setting {@code ekadashiProhibited} true is permitted only to a Temple Admin
 * (MANAGE_DIETARY_POLICY), checked in the service; for everyone else it must be false.
 *
 * <p>{@code supply} deliberately carries no such split. Deciding an ingredient is prohibited is a
 * religious-compliance call; saying a thing is a mop is not, so it is ordinary catalogue editing
 * under {@code MANAGE_RECIPES} and no second permission was invented for it (D-1).
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

		/** Optional Ekadashi-prohibited (grain/bean) flag; false unless a Temple Admin sets it. */
		boolean ekadashiProhibited,

		/**
		 * Whether this is a consumable supply — LPG, leaf plates, dishwashing liquid — rather than
		 * food (D-1). A primitive, so an absent key deserialises to {@code false}, which is the
		 * permissive answer: unflagged means food and food reaches the recipe picker. That is why
		 * the client type declares it required rather than optional, so every caller says which it
		 * is out loud instead of relying on this default.
		 */
		boolean supply,

		/** Optional alternate names, matched by typeahead alongside the name. */
		List<@Size(max = 200, message = "That alias is too long.") String> aliases) {
}
