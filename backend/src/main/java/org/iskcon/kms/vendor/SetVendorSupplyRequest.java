package org.iskcon.kms.vendor;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Map an ingredient to a vendor (E5-S1), with an optional list price and whether this vendor is
 * the preferred source for it. Marking preferred clears any other vendor's preference for the same
 * ingredient. The same record is one row of the bulk onboarding table (R-VEN-1) and the ingredient
 * page's vendor link (R-ING-2), so there is one shape for "this vendor supplies this".
 *
 * @param lastPrice the list price per one canonical unit, typed, when the vendor sells in the stock
 *     unit itself. When {@code pricePerPack} is sent this is ignored and the server derives it from
 *     the pack: a price is never typed twice (§3).
 * @param leadTimeDays how many days this vendor takes to deliver this ingredient once asked (T-090),
 *     or null where nobody has recorded it. <strong>Null is not zero.</strong> An absent lead time
 *     means the question has never been answered, and the ordering screens fall back to
 *     {@link LeadTimes#ASSUMED_LEAD_TIME_DAYS} rather than planning as though the goods arrive the
 *     same day. Zero is a real and different answer: cash-and-carry, the shop somebody walks into.
 *     Bounded at 365 by the column's own CHECK as well, for the reason V119 gives — this number is
 *     subtracted from a date, and a mistyped 3650 turns every screen red at once.
 * @param packSizeId "Sells it as" (R-VEN-1): one of this ingredient's pack sizes, or null for the
 *     stock unit itself. A pack of a different ingredient is refused with {@code KMS-400162}, and a
 *     per-unit {@code lastPrice} sent beside a pack with no {@code pricePerPack} with
 *     {@code KMS-400163}: with a pack, the price is typed per pack only.
 * @param pricePerPack the list price per that pack, in rupees, or null
 */
public record SetVendorSupplyRequest(
		@NotNull(message = "Choose an ingredient.") UUID ingredientId,
		@PositiveOrZero(message = "A price cannot be less than nothing.") BigDecimal lastPrice,
		@PositiveOrZero(message = "A lead time is between 0 and 365 days.")
		@Max(value = 365, message = "A lead time is between 0 and 365 days.")
		Integer leadTimeDays,
		boolean preferred,
		UUID packSizeId,
		@PositiveOrZero(message = "A price cannot be less than nothing.") BigDecimal pricePerPack) {

	/**
	 * A price per pack needs the pack it is per. Without one there is nothing to divide by, and V144
	 * refuses the row anyway ({@code vendor_supplies_pack_price_needs_pack}); saying so here gives
	 * the person a sentence instead of a server error.
	 */
	@AssertTrue(message = "Choose what it is sold as before giving a price per pack.")
	public boolean isPackPriceGivenWithAPack() {
		return pricePerPack == null || packSizeId != null;
	}
}
