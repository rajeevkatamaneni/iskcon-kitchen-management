package org.iskcon.kms.recipe;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A request to add a recipe category. */
public record CreateCategoryRequest(

		@NotBlank(message = "Enter a category name.")
		@Size(max = 100, message = "That name is too long.")
		String name,

		boolean fastingCompatible) {
}
