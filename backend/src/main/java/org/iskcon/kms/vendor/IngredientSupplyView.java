package org.iskcon.kms.vendor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One vendor that supplies an ingredient, seen from the ingredient's page (R-ING-2).
 *
 * <p>Every field of {@link VendorSupplyView}, flat, plus the vendor it belongs to — the client type
 * is {@code IngredientSupplyView extends VendorSupplyView}, so the JSON has to be one flat object and
 * not a nested one. Written out rather than {@code @JsonUnwrapped}, because a flat record is a thing
 * a reader can check against the client type field by field.
 */
public record IngredientSupplyView(
		UUID vendorId,
		String vendorName,
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

	static IngredientSupplyView of(UUID vendorId, String vendorName, VendorSupplyView s) {
		return new IngredientSupplyView(vendorId, vendorName, s.ingredientId(), s.ingredientName(),
				s.lastPrice(), s.unit(), s.packSizeId(), s.packLabel(), s.pricePerPack(),
				s.previousPrice(), s.previousPriceOn(), s.leadTimeDays(), s.preferred());
	}
}
