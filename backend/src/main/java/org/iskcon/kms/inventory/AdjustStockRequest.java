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
 */
public record AdjustStockRequest(
		UUID batchId,
		@NotNull BigDecimal quantity,
		@NotNull String unit,
		@NotNull AdjustmentReason reason,
		@Size(max = 500) String note) {
}
