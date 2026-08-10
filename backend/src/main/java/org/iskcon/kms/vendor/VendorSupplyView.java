package org.iskcon.kms.vendor;

import java.math.BigDecimal;
import java.util.UUID;

/** An ingredient a vendor supplies, with its last-known price and whether it's the preferred source. */
public record VendorSupplyView(
		UUID ingredientId,
		String ingredientName,
		BigDecimal lastPrice,
		boolean preferred) {
}
