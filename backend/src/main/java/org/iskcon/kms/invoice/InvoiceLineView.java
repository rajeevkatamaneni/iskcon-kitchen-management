package org.iskcon.kms.invoice;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * An invoice's saved item (R-INV-4, R-INV-7).
 *
 * <p>{@code orderedQty} and {@code deliveredQty} are set only on a delivered line, stated in the
 * line's own {@code unit} so the three quantities on one row compare without the screen converting
 * anything. {@code rate} is Amount ÷ Billed qty per one {@code unit} — the database's generated
 * column, so it cannot disagree with the two figures — and null when nothing was billed.
 * {@code ratePerPack} is Amount ÷ packCount on a line billed in packs ("₹1,500 / bag"), else null.
 */
public record InvoiceLineView(
		UUID id,
		UUID goodsReceiptLineId,
		UUID ingredientId,
		String itemName,
		BigDecimal orderedQty,
		BigDecimal deliveredQty,
		BigDecimal billedQty,
		String unit,
		UUID packSizeId,
		String packLabel,
		BigDecimal packQuantity,
		BigDecimal packCount,
		BigDecimal amount,
		BigDecimal rate,
		BigDecimal ratePerPack) {
}
