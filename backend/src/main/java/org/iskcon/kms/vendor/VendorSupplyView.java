package org.iskcon.kms.vendor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * An ingredient a vendor supplies, with its last-known price, how long it takes to arrive, and
 * whether it's the preferred source.
 *
 * @param leadTimeDays days between asking and delivery (T-090), or null where nobody has recorded
 *     it. The screen prints an em dash for null rather than a nought — an unanswered question and a
 *     same-day delivery are not the same fact and must not look alike.
 */
public record VendorSupplyView(
		UUID ingredientId,
		String ingredientName,
		BigDecimal lastPrice,
		Integer leadTimeDays,
		boolean preferred) {
}
