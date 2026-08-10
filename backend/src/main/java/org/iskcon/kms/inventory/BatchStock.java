package org.iskcon.kms.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One batch's remaining stock, for the detail view. Batches are presented first-expiry-first (FEFO):
 * the batch to draw from next is the one nearest its expiry, so the kitchen uses stock in the order
 * that wastes the least. {@code quantity} is in the ingredient's canonical unit.
 */
public record BatchStock(
		UUID batchId,
		BigDecimal quantity,
		String unit,
		LocalDate expiryDate,
		LocalDate receivedDate,
		boolean expiringSoon) {
}
