package org.iskcon.kms.receiving;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An order line as the Deliveries screen and its per-item history see it (R-DEL-2, R-DEL-4).
 * Catalogue lines only: a described line (four plastic stools) can never be received, so it has no
 * history to show here and is accounted for on the order's own page instead (T-066).
 *
 * <p><strong>No price anywhere</strong> (R-DEL-1, R-DEL-5). A price belongs to the invoice.
 *
 * <p>{@code receivedQty} is what was <em>kept</em>, summed over every part. Rejected goods are not
 * in it, so they stay owed: {@code stillToCome} is {@code orderedQty − receivedQty}, never below 0.
 * That is the same arithmetic as {@code PurchaseOrderService.isFullyAccountedFor}, which decides when
 * the order itself becomes RECEIVED; the two must never disagree, or the screen would show an item
 * as still to come on an order the application already calls finished. A return to the vendor
 * (T-013) does not put anything back on {@code stillToCome} either, for the same reason — that sum
 * reads {@code received_qty} alone.
 *
 * <p>{@code completedOn} is the date of the part whose kept quantity first reached what was ordered,
 * and null while anything is still to come.
 *
 * <p>The three pack fields (R-SL-3, V146) are all null together on a line not ordered in a pack.
 * {@code packLabel} is the pack as an order words it, "Bag (25 Kg)"; {@code packQuantity} is one
 * pack's size in this line's {@code unit}. Every quantity here stays in {@code unit}: the screen turns
 * it into packs for entry and back again (R-DEL-3).
 *
 * <p>The wire shape is {@code DeliveryLineView} in {@code frontend/lib/api.ts}, field for field.
 */
public record DeliveryLineView(
		UUID poLineId,
		UUID poId,
		String poNumber,
		UUID vendorId,
		String vendorName,
		UUID ingredientId,
		String itemName,
		String unit,
		BigDecimal orderedQty,
		BigDecimal receivedQty,
		BigDecimal rejectedQty,
		BigDecimal returnedQty,
		BigDecimal stillToCome,
		LocalDate neededBy,
		LocalDate completedOn,
		String packLabel,
		BigDecimal packQuantity,
		BigDecimal packCount,
		List<DeliveryPartView> parts) {
}
