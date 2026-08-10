package org.iskcon.kms.ingredient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A request to edit an ingredient's descriptive fields. The sattvic-prohibited flag is deliberately
 * not here — it changes only through the dedicated, audited, admin-only endpoint.
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

		List<@Size(max = 200) String> aliases) {
}
