package org.iskcon.kms.inventory;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A manual correction to stock (E3-S7): a signed change, a reason, and — for
 * {@link AdjustmentReason#OTHER} — a note the service insists on. An adjustment normally targets a
 * batch, because physical stock is physical: spoilage, damage and miscounts happen to a particular
 * lot, not to an abstract total.
 *
 * <p>With one exception, and it is the first thing a temple does. A consumable it has just started
 * tracking has no batches at all — the sacks are on the shelf and the ledger has never heard of
 * them — and every route into the ledger (a purchase-order receipt, a donation) describes stock
 * arriving rather than stock already there. So the item sat at zero, badged "below reorder level",
 * with nothing on the screen that could tell it otherwise. A null batch means exactly that count:
 * what is on the shelf today, opening a batch of its own.
 *
 * @param batchId  the batch being corrected, or null to open one with what is on the shelf now
 * @param quantity signed change in {@code unit}: negative writes stock off, positive corrects it up
 * @param unit     the unit of {@code quantity}; must belong to the ingredient's measurement family
 * @param reason   why (mandatory)
 * @param note     free text; required when {@code reason} is OTHER
 * @param pricePerUnit "What it would cost to buy today" (R-ING-3), in rupees per one of the
 *                 ingredient's canonical unit whatever {@code unit} the count was typed in. Required,
 *                 and never 0, on every adjustment that adds stock, whatever its reason — including
 *                 the opening count on "Add to inventory" (a null batch). Saving it sets the
 *                 ingredient's market rate. Ignored on an adjustment that takes stock away.
 *                 Checked in the service rather than annotated, because whether it is required
 *                 depends on the other fields.
 */
public record AdjustStockRequest(
		UUID batchId,
		@NotNull(message = "Enter how much to add or take away.") BigDecimal quantity,
		@NotNull(message = "Choose a unit.") String unit,
		@NotNull(message = "Choose why the stock is being adjusted.") AdjustmentReason reason,
		@Size(max = 500, message = "That note is too long.") String note,
		BigDecimal pricePerUnit) {
}
