package org.iskcon.kms.shoppinglist;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.vendor.OrderUrgency;

/**
 * A line on the suggested shopping list (E5-S2). {@code shortfall}, {@code thresholdTopUp} and
 * {@code poOutstanding} are the provenance — how much each demand stream contributed — so the
 * reviewer sees why the quantity is what it is. {@code shortPurchaseOrders} names the POs whose
 * still-outstanding quantities re-fed this line (E5-S6), so a short delivery is traceable to its PO.
 *
 * @param neededBy the date written on the purchase order — when the temple wants the goods on the
 *     shelf, which is the earliest meal that demands them less a two-day delivery buffer. It is
 *     <strong>not</strong> the day the food is cooked and it is not an order-by date.
 * @param orderBy the last day this can be ordered and still arrive: the earliest meal that demands
 *     it, minus the lead time recorded against its preferred vendor (T-090). Null on a hand-added
 *     line, which no meal demanded and which therefore has no such date to compute.
 * @param leadTimeDays the recorded lead time that produced {@code orderBy}, or null where none was
 *     recorded and the two-day assumption stood in. <strong>Null means unknown, never same-day</strong>
 *     — it is carried so the screen can say whether the date came from the vendor or from us.
 * @param orderUrgency where today stands against {@code orderBy}, computed as the line is read
 *     rather than stored, because it changes at midnight without anything in the row changing. Null
 *     exactly when {@code orderBy} is.
 */
public record ShoppingListLineView(
		UUID ingredientId,
		String ingredientName,
		BigDecimal currentStock,
		String unit,
		BigDecimal suggestedQty,
		LocalDate neededBy,
		LocalDate orderBy,
		Integer leadTimeDays,
		OrderUrgency orderUrgency,
		UUID suggestedVendorId,
		String suggestedVendorName,
		BigDecimal shortfall,
		BigDecimal thresholdTopUp,
		BigDecimal poOutstanding,
		List<String> shortPurchaseOrders,
		boolean included,
		boolean edited) {
}
