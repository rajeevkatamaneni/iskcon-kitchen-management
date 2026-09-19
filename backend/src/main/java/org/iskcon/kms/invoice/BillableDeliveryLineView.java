package org.iskcon.kms.invoice;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of a delivery that can still be billed (R-INV-3). Quantities are in {@code unit}, the unit
 * the delivery was recorded in. {@code deliveredQty} is what the delivery kept — received, not
 * rejected — and the form's Billed qty starts from it. The pack is the one the order line was placed
 * in ("Bag (25 Kg)"), because the bill is in the unit the order used (R-INV-4); null when the order
 * was in a plain unit.
 */
public record BillableDeliveryLineView(
		UUID goodsReceiptLineId,
		UUID ingredientId,
		String itemName,
		BigDecimal orderedQty,
		BigDecimal deliveredQty,
		String unit,
		UUID packSizeId,
		String packLabel,
		BigDecimal packQuantity) {
}
