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
		@NotNull UUID ingredientId,
		@Size(max = 120) String storageLocation,
		@PositiveOrZero BigDecimal reorderThreshold,
		@Size(max = 1000) String notes) {
}
