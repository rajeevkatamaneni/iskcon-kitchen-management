package org.iskcon.kms.receiving;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Goods sent back to the vendor after they had been taken into stock (T-013).
 *
 * <p>{@code quantity} is positive — how much went back — and {@code stockMovementId} is the
 * negative {@code RETURN_TO_VENDOR} row it booked. The two together are the whole record: the
 * receipt it came from is unchanged and always will be.
 */
public record GoodsReturnView(
		UUID id,
		UUID receiptId,
		UUID receiptLineId,
		UUID ingredientId,
		String ingredientName,
		BigDecimal quantity,
		String unit,
		String reason,
		String note,
		String returnedByName,
		Instant returnedAt,
		UUID stockMovementId) {
}
