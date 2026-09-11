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
 *     shelf. Since T-130 that is <strong>the day of the earliest planned meal that demands them</strong>,
 *     with nothing subtracted. It used to carry a two-day delivery buffer, which was a guess standing
 *     in for the fact T-090 later recorded per vendor and ingredient — and applying both meant asking
 *     a supplier to deliver two days before the temple needed the food, then warning on the order
 *     screen that the same date gave that supplier too little notice.
 * @param orderBy the last day this can be ordered and still arrive: the earliest meal that demands
 *     it, minus the lead time recorded against its preferred vendor (T-090). Null on a hand-added
 *     line, which no meal demanded and which therefore has no such date to compute.
 * @param edited whether a person has decided something about this line — a quantity, an untick, or
 *     typing it in by hand. Since T-132 that is exactly "a row exists in {@code shopping_list_lines}
 *     for this ingredient", because nothing else is stored there.
 * @param excludedSince the temple's own day somebody unticked this line, and null while it is
 *     included. An untick persists, which has a real cost — one made in September silently suppresses
 *     a January shortfall, and between those dates the line is not on the screen for anybody to
 *     notice. This is the mitigation: when the ingredient is needed again the line comes back with
 *     the date on it, so a stale untick announces itself at the moment it starts to matter and
 *     re-ticking is one click.
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
		boolean edited,
		LocalDate excludedSince) {
}
