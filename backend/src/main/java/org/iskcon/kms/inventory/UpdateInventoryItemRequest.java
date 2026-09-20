package org.iskcon.kms.inventory;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Edit a tracked consumable's metadata: where it lives, when to reorder it, and notes. The
 * ingredient it tracks is fixed at creation — retracking a different ingredient is a new item.
 *
 * @param reorderThresholdUnit the unit {@code reorderThreshold} was typed in, or null to say it is
 *        already in the ingredient's own unit — which is what the inventory list's inline editor
 *        sends, because that row shows the unit as fixed text. The server converts and refuses; see
 *        {@code InventoryItemService.canonicalThreshold} for both refusals and why the unit travels.
 */
public record UpdateInventoryItemRequest(
		@Size(max = 120, message = "That location is too long.") String storageLocation,
		@PositiveOrZero(message = "A reorder level cannot be less than nothing.")
		BigDecimal reorderThreshold,
		@Size(max = 8, message = "That is not a unit.") String reorderThresholdUnit,
		@Size(max = 1000, message = "That note is too long.") String notes) {
}
