package org.iskcon.kms.wishlist;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A wish-list item (E7-S5) with its sponsorship progress. {@code sponsoredQuantity} of
 * {@code quantityWanted} drives the public progress bar and the auto-fulfilment flip.
 */
public record WishlistItemView(
		UUID id,
		String title,
		String description,
		String imageRef,
		BigDecimal priceInr,
		String category,
		int quantityWanted,
		int sponsoredQuantity,
		int sortOrder,
		String status,
		String note,
		Instant createdAt) {
}
