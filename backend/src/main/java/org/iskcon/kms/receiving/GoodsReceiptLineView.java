package org.iskcon.kms.receiving;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A recorded receipt line (E5-S6): what arrived good, what was rejected, its batch, and what was
 * paid for it.
 *
 * <p>{@code unitPrice} is in rupees per one of this line's {@code unit}. Null where no price was
 * given — a delivery ahead of its bill, or a gift in kind — and never to be read as zero. Who
 * recorded it and when are on the receipt this line belongs to, not repeated here.
 */
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
		LocalDate receivedDate,
		BigDecimal unitPrice) {
}
