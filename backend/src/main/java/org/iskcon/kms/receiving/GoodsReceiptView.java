package org.iskcon.kms.receiving;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A recorded delivery against a PO (E5-S6): its header and lines. */
public record GoodsReceiptView(
		UUID id,
		UUID purchaseOrderId,
		String deliveryNoteRef,
		String note,
		String receivedByName,
		Instant receivedAt,
		List<GoodsReceiptLineView> lines) {
}
