package org.iskcon.kms.ingredient.merge;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A vendor that supplies two or more of the group at different list prices, so the merge has to be
 * told whose price that vendor keeps (R-DUP-3 step 4). Mirrors {@code MergeSupplyConflictView} in
 * {@code frontend/lib/api.ts}.
 */
public record MergeSupplyConflictView(UUID vendorId, String vendorName, List<Price> prices) {

	/**
	 * One of the vendor's prices, as it stands today on that ingredient.
	 *
	 * @param listPrice rupees per one of {@code unit} — the ingredient's own canonical unit, exactly
	 *     as the vendor page shows it, not converted
	 * @param unit that ingredient's canonical unit as stored ("KG")
	 * @param packLabel the pack the vendor sells it in, as the pack chip reads ("Bag = 25 Kg"), or
	 *     null
	 */
	public record Price(
			UUID ingredientId, String ingredientName, BigDecimal listPrice, String unit, String packLabel) {
	}
}
