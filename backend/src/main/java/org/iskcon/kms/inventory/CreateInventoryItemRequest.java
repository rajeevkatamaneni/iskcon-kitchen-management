package org.iskcon.kms.inventory;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Start tracking a consumable. The reorder threshold is optional — an item with none simply raises
 * no low-stock alert until a temple sets one. There is no stock field: stock only ever comes from
 * the movement ledger.
 */
public record CreateInventoryItemRequest(
		@NotNull(message = "Choose an ingredient.") UUID ingredientId,
		@Size(max = 120, message = "That location is too long.") String storageLocation,
		@PositiveOrZero(message = "A reorder level cannot be less than nothing.")
		BigDecimal reorderThreshold,
		@Size(max = 1000, message = "That note is too long.") String notes) {
}
