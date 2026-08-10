package org.iskcon.kms.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.iskcon.kms.ingredient.Unit;

/**
 * A single stock change, handed to {@link StockMovementService#record}. This is the internal
 * kernel command every write path builds — a receipt (E5), a donation (E3-S5), consumption
 * (E3-S6), an adjustment (E3-S7) — so the shape of a movement and the rule that it is signed and
 * batch-scoped live in exactly one place.
 *
 * @param ingredientId  which consumable moved
 * @param storageLocation which store-room, or null for the tenant's default
 * @param batchId       the physical batch this movement belongs to. A batch-establishing movement
 *                      (receipt, donation, positive count correction) mints a fresh id; consumption
 *                      and corrections reuse an existing one
 * @param quantity      signed: positive adds stock, negative removes it. Never zero
 * @param unit          the unit {@code quantity} is expressed in
 * @param type          why it moved
 * @param expiryDate    when this batch expires, on a batch-establishing movement; else null
 * @param receivedDate  when this batch arrived, on a batch-establishing movement; else null
 * @param reason        required for {@link MovementType#ADJUSTMENT}, null otherwise
 * @param referenceType what this movement points back to, or null
 * @param referenceId   the id of that thing, or null
 * @param note          free-text context; required by callers when {@code reason} is
 *                      {@link AdjustmentReason#OTHER}
 */
public record RecordMovement(
		UUID ingredientId,
		String storageLocation,
		UUID batchId,
		BigDecimal quantity,
		Unit unit,
		MovementType type,
		LocalDate expiryDate,
		LocalDate receivedDate,
		AdjustmentReason reason,
		MovementReference referenceType,
		UUID referenceId,
		String note) {
}
