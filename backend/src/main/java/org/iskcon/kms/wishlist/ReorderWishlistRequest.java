package org.iskcon.kms.wishlist;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

/** The item ids in their new public display order (E7-S5). */
public record ReorderWishlistRequest(@NotEmpty List<UUID> itemIds) {
}
