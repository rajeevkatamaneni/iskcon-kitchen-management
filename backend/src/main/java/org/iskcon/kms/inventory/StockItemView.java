package org.iskcon.kms.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A consumable in the stock list (E3-S1): what a temple tracks, and how much of it there is right
 * now. {@code onHand} is not stored anywhere — it is the sum of the item's ledger movements,
 * expressed in the ingredient's canonical unit. The two flags are the badges the list shows at a
 * glance: below the reorder level, and holding a batch that expires soon.
 */
public record StockItemView(
		UUID itemId,
		UUID ingredientId,
		String ingredientName,
		String category,
		String storageLocation,
		String unit,
		BigDecimal onHand,
		BigDecimal reorderThreshold,
		boolean belowThreshold,
		boolean expiringSoon,
		LocalDate soonestExpiry,
		String notes) {
}
