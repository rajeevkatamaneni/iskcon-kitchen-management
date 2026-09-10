package org.iskcon.kms.wishlist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Edit a wish-list item (E7-S5). */
public record UpdateWishlistItemRequest(
		@NotBlank(message = "Give the item a name.")
		@Size(max = 200, message = "That name is too long.")
		String title,
		@Size(max = 2000, message = "That description is too long.") String description,
		@Size(max = 500, message = "That image reference is too long.") String imageRef,
		@NotNull(message = "Enter what it costs.")
		@Positive(message = "A price has to be more than zero.")
		BigDecimal priceInr,
		@NotBlank(message = "Choose a category.") String category,
		@Positive(message = "Ask for at least one.") int quantityWanted,
		@Size(max = 500, message = "That note is too long.") String note) {
}
