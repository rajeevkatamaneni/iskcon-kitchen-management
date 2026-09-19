package org.iskcon.kms.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Start tracking a consumable. The reorder threshold is optional — an item with none simply raises
 * no low-stock alert until a temple sets one. There is no stock field: stock only ever comes from
 * the movement ledger.
 *
 * <p><strong>The opening count rides along, and is still a movement (T-294).</strong> "Add to
 * inventory" asks how much is on the shelf, and used to send that as a second request once the item
 * existed. When the second one failed — a refusal, or simply the API restarting between the two —
 * the item was left behind with no stock and no value, gone from the Add list, and the only way to
 * finish was to find the item and count it again (VERIFY-A defect 5). So the count now arrives in the
 * same request and is written in the same transaction: either the item and its first lot both exist,
 * or neither does. It is still not a stock <em>field</em>. The service turns it into exactly the
 * adjustment the second request used to make, through the same code, so the ledger remains the only
 * place stock lives.
 *
 * @param openingCount what is on the shelf now, or null for none (the item then starts at zero, as it
 *                     always could)
 */
public record CreateInventoryItemRequest(
		@NotNull(message = "Choose an ingredient.") UUID ingredientId,
		@Size(max = 120, message = "That location is too long.") String storageLocation,
		@PositiveOrZero(message = "A reorder level cannot be less than nothing.")
		BigDecimal reorderThreshold,
		@Size(max = 1000, message = "That note is too long.") String notes,
		@Valid OpeningCount openingCount) {

	/**
	 * The first count of a new item: how much, in which unit, and what it would cost to buy today.
	 *
	 * <p>Only the presence of the quantity and unit is annotated. Everything that depends on the
	 * ingredient — the unit's family (BL-9), a count of zero, the value being required and above 0
	 * (KMS-400161, R-ING-3), the Temple Admin's signature on a first count — is the adjustment's rule
	 * and is asked by {@link InventoryItemService#adjust}, not restated here, so the two ways of
	 * recording an opening count cannot drift apart.
	 *
	 * @param quantity     how much is on the shelf, in {@code unit}
	 * @param unit         the unit it was counted in; must belong to the ingredient's family
	 * @param pricePerUnit rupees per one of the ingredient's stock unit, whatever {@code unit} is
	 */
	public record OpeningCount(
			@NotNull(message = "Enter how much is on the shelf.") BigDecimal quantity,
			@NotNull(message = "Choose a unit.") String unit,
			BigDecimal pricePerUnit) {
	}
}
