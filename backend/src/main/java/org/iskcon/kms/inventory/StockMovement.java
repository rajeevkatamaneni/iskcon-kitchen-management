package org.iskcon.kms.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of the ledger, as read back for a history view. The actor is shown by name where the row
 * belongs to a user still visible in the tenant; {@code actorName} is null otherwise, and the id
 * always remains.
 */
public record StockMovement(
		UUID id,
		UUID ingredientId,
		String ingredientName,
		String storageLocation,
		UUID batchId,
		BigDecimal quantity,
		String unit,
		MovementType type,
		LocalDate expiryDate,
		LocalDate receivedDate,
		AdjustmentReason reason,
		MovementReference referenceType,
		UUID referenceId,
		String note,
		UUID actorUserId,
		String actorName,
		Instant createdAt) {
}
