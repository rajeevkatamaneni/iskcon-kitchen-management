package org.iskcon.kms.receiving;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A recorded receipt line (E5-S6): what arrived good, what was rejected, and its batch. */
public record GoodsReceiptLineView(
		UUID id,
		UUID poLineId,
		UUID ingredientId,
		String ingredientName,
		BigDecimal receivedQty,
		BigDecimal rejectedQty,
		String rejectReason,
		String unit,
		UUID batchId,
		LocalDate expiryDate,
		LocalDate receivedDate) {
}
