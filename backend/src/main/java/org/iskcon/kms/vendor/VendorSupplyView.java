package org.iskcon.kms.vendor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An ingredient a vendor supplies, with its list price, how long it takes to arrive, and whether
 * it's the preferred source.
 *
 * @param lastPrice the list price per one canonical unit (shown as "List price"; the column is still
 *     {@code last_price}). Derived from {@code pricePerPack} when the vendor sells in packs.
 * @param unit the ingredient's canonical unit as stored ("KG"), for "₹60 / Kg"
 * @param packSizeId "Sells it as": one of the ingredient's pack sizes, or null for the stock unit
 * @param packLabel the pack as a chip reads ("Bag = 25 Kg"), or null
 * @param pricePerPack the list price per that pack, or null
 * @param previousPrice the list price before the current one, per canonical unit, from the price
 *     history (R-VEN-3); null when there is no earlier price
 * @param previousPriceOn the day that previous price took effect; null exactly when it is
 * @param leadTimeDays days between asking and delivery (T-090), or null where nobody has recorded
 *     it. The screen prints an em dash for null rather than a nought — an unanswered question and a
 *     same-day delivery are not the same fact and must not look alike.
 */
public record VendorSupplyView(
		UUID ingredientId,
		String ingredientName,
		BigDecimal lastPrice,
		String unit,
		UUID packSizeId,
		String packLabel,
		BigDecimal pricePerPack,
		BigDecimal previousPrice,
		LocalDate previousPriceOn,
		Integer leadTimeDays,
		boolean preferred) {
}
