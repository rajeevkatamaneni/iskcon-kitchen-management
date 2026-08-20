package org.iskcon.kms.costing;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * An ingredient the day's cooking needs that could not be priced (B2). The quantity travels with it
 * so a reader can tell a forgotten sack of rice from a forgotten pinch of asafoetida — the first is
 * why the estimate is low, the second is a rounding error wearing the same badge.
 */
public record UnpricedIngredient(
		UUID ingredientId,
		String name,
		BigDecimal quantity,
		String unit) {
}
