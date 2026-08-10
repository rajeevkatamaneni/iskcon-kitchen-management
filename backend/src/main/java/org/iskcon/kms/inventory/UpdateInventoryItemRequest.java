package org.iskcon.kms.inventory;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Edit a tracked consumable's metadata: where it lives, when to reorder it, and notes. The
 * ingredient it tracks is fixed at creation — retracking a different ingredient is a new item.
 */
public record UpdateInventoryItemRequest(
		@Size(max = 120) String storageLocation,
		@PositiveOrZero BigDecimal reorderThreshold,
		@Size(max = 1000) String notes) {
}
