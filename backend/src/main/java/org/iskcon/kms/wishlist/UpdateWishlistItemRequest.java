package org.iskcon.kms.wishlist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Edit a wish-list item (E7-S5). */
public record UpdateWishlistItemRequest(
		@NotBlank @Size(max = 200) String title,
		@Size(max = 2000) String description,
		@Size(max = 500) String imageRef,
		@NotNull @Positive BigDecimal priceInr,
		@NotBlank String category,
		@Positive int quantityWanted,
		@Size(max = 500) String note) {
}
