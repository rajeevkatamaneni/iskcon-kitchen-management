package org.iskcon.kms.inventory;

import java.util.List;

/**
 * A consumable with its stock broken out by batch (E3-S1). The header carries the totals and badges;
 * {@code batches} lists what's actually on the shelf, FEFO-ordered.
 */
public record StockDetailView(
		StockItemView item,
		List<BatchStock> batches) {
}
