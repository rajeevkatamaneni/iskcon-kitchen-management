package org.iskcon.kms.wishlist;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A wish-list item (E7-S5) with its progress. What the item costs is {@code priceInr} times
 * {@code quantityWanted}, and {@code paidInr} of that has been given; the two drive the progress
 * bar a devotee sees and the auto-fulfilment flip.
 */
public record WishlistItemView(
		UUID id,
		String title,
		String description,
		String imageRef,
		BigDecimal priceInr,
		String category,
		int quantityWanted,
		/** Money given towards this item so far. Progress is rupees, not units: the temple buys the
		 * thing whole, and a devotee may put any part of it in. */
		BigDecimal paidInr,
		int sortOrder,
		String status,
		String note,
		Instant createdAt) {
}
