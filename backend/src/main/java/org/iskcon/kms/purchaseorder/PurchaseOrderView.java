package org.iskcon.kms.purchaseorder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A purchase order header (E5-S3), as shown in the list. */
public record PurchaseOrderView(
		UUID id,
		String poNumber,
		UUID vendorId,
		String vendorName,
		PoStatus status,
		LocalDate orderDate,
		LocalDate neededBy,
		String deliveryLocation,
		String notes,
		String cancelReason,
		Instant sentAt,
		Instant cancelledAt,
		Instant createdAt) {
}
