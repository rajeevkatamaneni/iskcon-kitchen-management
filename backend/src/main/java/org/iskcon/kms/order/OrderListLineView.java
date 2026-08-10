package org.iskcon.kms.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A line on the suggested order list (E5-S2). {@code shortfall} and {@code thresholdTopUp} are the
 * provenance — how much each demand stream contributed — so the reviewer sees why the quantity is
 * what it is.
 */
public record OrderListLineView(
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
		boolean included,
		boolean edited) {
}
