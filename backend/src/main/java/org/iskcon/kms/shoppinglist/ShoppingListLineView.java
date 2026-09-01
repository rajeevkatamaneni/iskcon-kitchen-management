package org.iskcon.kms.shoppinglist;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A line on the suggested shopping list (E5-S2). {@code shortfall}, {@code thresholdTopUp} and
 * {@code poOutstanding} are the provenance — how much each demand stream contributed — so the
 * reviewer sees why the quantity is what it is. {@code shortPurchaseOrders} names the POs whose
 * still-outstanding quantities re-fed this line (E5-S6), so a short delivery is traceable to its PO.
 */
public record ShoppingListLineView(
		UUID ingredientId,
		String ingredientName,
		BigDecimal currentStock,
		String unit,
		BigDecimal suggestedQty,
		LocalDate neededBy,
		UUID suggestedVendorId,
		String suggestedVendorName,
		BigDecimal shortfall,
		BigDecimal thresholdTopUp,
		BigDecimal poOutstanding,
		List<String> shortPurchaseOrders,
		boolean included,
		boolean edited) {
}
