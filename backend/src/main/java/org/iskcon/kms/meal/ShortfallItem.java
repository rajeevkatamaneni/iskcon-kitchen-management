package org.iskcon.kms.meal;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One ingredient's total shortfall across the planning horizon (E4-S5) — the contract the ordering
 * pipeline (E5-S2) consumes to know how much to buy.
 */
public record ShortfallItem(
		UUID ingredientId,
		String ingredientName,
		BigDecimal shortBy,
		String unit) {
}
