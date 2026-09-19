package org.iskcon.kms.invoice;

import java.time.LocalDate;
import java.util.UUID;

/** A delivery an invoice bills: "PO-2026-0044 · delivered 12 Sept · received by Govinda Das". */
public record InvoiceDeliveryView(
		UUID receiptId,
		UUID purchaseOrderId,
		String poNumber,
		LocalDate receivedOn,
		String receivedByName) {
}
