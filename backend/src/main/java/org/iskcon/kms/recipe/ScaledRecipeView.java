package org.iskcon.kms.recipe;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A recipe scaled to a target yield: the ratio applied, and every line's scaled quantities. */
public record ScaledRecipeView(
		UUID id,
		String name,
		BigDecimal baseYieldQty,
		String baseYieldUnit,
		BigDecimal targetYield,
		BigDecimal ratio,
		List<ScaledLine> ingredients) {
}
