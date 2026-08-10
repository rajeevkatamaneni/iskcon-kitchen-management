package org.iskcon.kms.purchaseorder;

import java.util.List;

/** A purchase order with its lines and activity trail (E5-S3). */
public record PurchaseOrderDetailView(
		PurchaseOrderView order,
		List<PurchaseOrderLineView> lines,
		List<PoEventView> events) {
}
