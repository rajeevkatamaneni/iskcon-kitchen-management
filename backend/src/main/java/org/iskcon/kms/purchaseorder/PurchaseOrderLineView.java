package org.iskcon.kms.purchaseorder;

import java.math.BigDecimal;
import java.util.UUID;

/** A line on a purchase order. {@code expectedPrice} is optional — the temple may negotiate on delivery. */
public record PurchaseOrderLineView(
		UUID id,
		UUID ingredientId,
		String ingredientName,
		BigDecimal quantity,
		String unit,
		BigDecimal expectedPrice) {
}
