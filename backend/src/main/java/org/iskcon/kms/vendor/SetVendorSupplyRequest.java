package org.iskcon.kms.vendor;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Map an ingredient to a vendor (E5-S1), with an optional last-known price and whether this vendor is
 * the preferred source for it. Marking preferred clears any other vendor's preference for the same
 * ingredient.
 *
 * @param leadTimeDays how many days this vendor takes to deliver this ingredient once asked (T-090),
 *     or null where nobody has recorded it. <strong>Null is not zero.</strong> An absent lead time
 *     means the question has never been answered, and the ordering screens fall back to
 *     {@link LeadTimes#ASSUMED_LEAD_TIME_DAYS} rather than planning as though the goods arrive the
 *     same day. Zero is a real and different answer: cash-and-carry, the shop somebody walks into.
 *     Bounded at 365 by the column's own CHECK as well, for the reason V119 gives — this number is
 *     subtracted from a date, and a mistyped 3650 turns every screen red at once.
 */
public record SetVendorSupplyRequest(
		@NotNull UUID ingredientId,
		@PositiveOrZero BigDecimal lastPrice,
		@PositiveOrZero @Max(365) Integer leadTimeDays,
		boolean preferred) {
}
