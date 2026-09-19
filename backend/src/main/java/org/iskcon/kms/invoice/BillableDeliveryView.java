package org.iskcon.kms.invoice;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A delivery from this vendor that no standing invoice bills yet (R-INV-3): "PO-2026-0044 ·
 * delivered 12 Sept · received by Govinda Das". A voided invoice releases its deliveries, so a
 * delivery billed on a struck bill is offered again. {@code receivedOn} is the temple's day, not the
 * server's.
 */
public record BillableDeliveryView(
		UUID receiptId,
		UUID purchaseOrderId,
		String poNumber,
		LocalDate receivedOn,
		String receivedByName,
		List<BillableDeliveryLineView> lines) {
}
