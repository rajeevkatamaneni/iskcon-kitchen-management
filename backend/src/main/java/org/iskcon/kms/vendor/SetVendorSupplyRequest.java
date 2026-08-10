package org.iskcon.kms.vendor;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Map an ingredient to a vendor (E5-S1), with an optional last-known price and whether this vendor is
 * the preferred source for it. Marking preferred clears any other vendor's preference for the same
 * ingredient.
 */
public record SetVendorSupplyRequest(
		@NotNull UUID ingredientId,
		@PositiveOrZero BigDecimal lastPrice,
		boolean preferred) {
}
