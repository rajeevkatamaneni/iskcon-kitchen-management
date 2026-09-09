package org.iskcon.kms.inventory;

import java.util.List;

/**
 * A consumable with its stock broken out by batch (E3-S1). The header carries the totals and badges;
 * {@code batches} lists what's actually on the shelf, FEFO-ordered.
 *
 * @param committed the meals that claimed this ingredient's stock, in the order they will be cooked
 *                  (T-086). The inventory table carries committed as a <em>total</em>, which is what
 *                  a storekeeper scans a column for; this is the list, which answers the question
 *                  the total provokes — <em>which meals?</em> — and is the only place it is
 *                  answerable. Empty where nothing has claimed it.
 */
public record StockDetailView(
		StockItemView item,
		List<BatchStock> batches,
		List<CommittedMeal> committed) {
}
