package org.iskcon.kms.receiving;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One delivery's part against one order line (R-DEL-4): "12 Sept · 30 Kg received · 2 Kg rejected
 * (spoiled) · Received by: Karuna Murti Das".
 *
 * <p><strong>Deliberately carries no order number.</strong> R-DEL-4 says so in as many words: the
 * per-item history is read under the item, and the item already says which order it is on. The wire
 * shape is {@code DeliveryPartView} in {@code frontend/lib/api.ts}, field for field.
 *
 * <p>{@code receivedOn} is the temple's date of the receipt it belongs to — the day the goods were
 * recorded as arriving, in the temple's own zone ({@code TempleClock}), never the server's.
 */
public record DeliveryPartView(
		UUID receiptId,
		LocalDate receivedOn,
		BigDecimal receivedQty,
		BigDecimal rejectedQty,
		RejectReason rejectReason,
		String receivedByName) {
}
