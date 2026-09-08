package org.iskcon.kms.ingredient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A request to edit an ingredient's descriptive fields. The Ekadashi-prohibited flag is deliberately
 * not here — it changes only through the dedicated, audited, admin-only endpoint.
 *
 * <p>The supply flag <em>is</em> here, and the difference is the point of D-1. A thing can stop
 * being a supply or start being one — a temple that catalogued its leaf plates as food fixes that
 * by editing the row — and there is no religious-policy decision in it, so it rides along with the
 * name and the category under {@code MANAGE_RECIPES} rather than getting an endpoint and a
 * permission of its own the way the Ekadashi flag has.
 */
public record UpdateIngredientRequest(

		@NotBlank(message = "Enter the ingredient's name.")
		@Size(max = 200, message = "That name is too long.")
		String name,

		@NotBlank(message = "Choose a category.")
		@Size(max = 100, message = "That category name is too long.")
		String category,

		@NotBlank(message = "Choose a unit.")
		String unit,

		/**
		 * Whether this is a supply rather than food (D-1). Primitive, so omitting it from the JSON
		 * silently turns a supply back into food — which is why the client type requires it and the
		 * catalogue's edit row always sends the value it is showing.
		 */
		boolean supply,

		List<@Size(max = 200) String> aliases) {
}
