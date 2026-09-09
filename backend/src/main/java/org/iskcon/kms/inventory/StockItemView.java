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
		String notes) {
}
