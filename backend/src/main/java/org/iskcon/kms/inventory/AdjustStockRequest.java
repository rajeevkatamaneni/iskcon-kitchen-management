package org.iskcon.kms.inventory;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A manual correction to a specific batch's stock (E3-S7): a signed change, a reason, and — for
 * {@link AdjustmentReason#OTHER} — a note the service insists on. The adjustment targets a batch
 * because physical stock is physical: spoilage, damage and miscounts happen to a particular lot, not
 * to an abstract total.
 *
 * @param batchId  the batch being corrected
 * @param quantity signed change in {@code unit}: negative writes stock off, positive corrects it up
 * @param unit     the unit of {@code quantity}; must belong to the ingredient's measurement family
 * @param reason   why (mandatory)
 * @param note     free text; required when {@code reason} is OTHER
 */
public record AdjustStockRequest(
		@NotNull UUID batchId,
		@NotNull BigDecimal quantity,
		@NotNull String unit,
		@NotNull AdjustmentReason reason,
		@Size(max = 500) String note) {
}
