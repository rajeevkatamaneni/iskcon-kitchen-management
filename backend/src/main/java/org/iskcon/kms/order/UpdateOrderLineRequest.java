package org.iskcon.kms.order;

import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A human edit to an order-list line (E5-S2): quantity, chosen vendor, and whether to include it.
 * Editing marks the line so a regeneration leaves it untouched.
 */
public record UpdateOrderLineRequest(
		@Positive BigDecimal suggestedQty,
		UUID suggestedVendorId,
		boolean included) {
}
