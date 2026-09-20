package org.iskcon.kms.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A consumable in the stock list (E3-S1): what a temple tracks, and how much of it there is right
 * now. Batches are shown first-expiry-first on the detail screen; the two flags here are the badges
 * the list shows at a glance.
 *
 * <p>Three figures, none of them stored (T-086). {@code onHand} is the sum of the item's ledger
 * movements, expressed in the ingredient's canonical unit — a physical fact that only a real
 * movement changes. {@code committed} is what the saved plan intends to draw out of it; see
 * {@link CommittedStockService} for exactly which plans count and why the ones that do not are
 * excluded. {@code available} is the subtraction of the two, and it is the figure a person actually
 * needs: 415 kg of ash gourd with 410 kg promised to Sunday is not 415 kg of ash gourd.
 *
 * @param available on hand minus committed, computed on every read and never written anywhere. It
 *                  may be negative, and a negative one is not an error: it says the plan has
 *                  promised more than the store holds, which is a true thing that has happened and
 *                  wants fixing rather than hiding.
 * @param belowThreshold what the list badges as <em>Low</em>. It judges {@code available}, not
 *                  {@code onHand} — that is the whole point of the three figures, and it is the one
 *                  behaviour change a reader of this record should not miss. It is also true
 *                  whenever {@code available} is negative, regardless of the reorder level: an item
 *                  more than fully committed is not <em>Fine</em>, and an item nobody has ever set a
 *                  level for would otherwise read <em>Fine</em> while over-promised.
 *                  <p>It is <strong>false for an ingredient the temple has marked as one it never
 *                  buys</strong> (T-430), however far under it runs. <em>Low</em> is an instruction
 *                  to go and buy, and there is no buying water. The client's question is "should
 *                  somebody act about this", and this single boolean is the whole answer to it —
 *                  which is why the mark itself is deliberately <em>not</em> a field on this record.
 *                  Every reader asks the same question, so every reader gets the same answer without
 *                  combining two flags and risking combining them differently: the low-stock
 *                  endpoint, the nightly digest, the dashboard's count and the inventory screen's own
 *                  count all read this one. The reasoning is set out in full on
 *                  {@code InventoryItemService.isBelowThreshold}.
 *
 * <h2>The three facts added in T-432</h2>
 *
 * <p>Rajeev, 2026-09-20, reviewing this screen: it "is very confusing and not up to the standard of
 * other pages in our app". Three of the things he named are facts the record did not carry, and all
 * three are computed in aggregate by {@link StockFactsService} — never per row.
 *
 * @param lastCounted the temple's own day somebody last counted this shelf, or null where nobody
 *                  ever has. Only a stock-take counts; see
 *                  {@link StockFactsService#lastCountedByIngredient}.
 * @param onOrder what a vendor has been asked for and not yet delivered, in the ingredient's
 *                  canonical unit, or <strong>null where nothing is on order</strong>. Null rather
 *                  than zero, for the reason {@code format.ts} gives for the same distinction on the
 *                  screen: a quantity nobody has is not a zero. A draft order is not on order — see
 *                  {@link StockFactsService#onOrderBaseByIngredient}.
 * @param lastsFor roughly how long what is on the shelf lasts, or <strong>null where there is not
 *                  enough history to judge</strong>. The null is the honest answer and the screen is
 *                  required to say so in words rather than print a figure. See {@link StockCover}.
 */
public record StockItemView(
		UUID itemId,
		UUID ingredientId,
		String ingredientName,
		String category,
		String storageLocation,
		String unit,
		BigDecimal onHand,
		BigDecimal committed,
		BigDecimal available,
		BigDecimal reorderThreshold,
		boolean belowThreshold,
		boolean expiringSoon,
		LocalDate soonestExpiry,
		String notes,
		LocalDate lastCounted,
		BigDecimal onOrder,
		StockCover lastsFor) {
}
