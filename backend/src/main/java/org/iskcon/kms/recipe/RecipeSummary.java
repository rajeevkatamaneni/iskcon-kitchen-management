package org.iskcon.kms.recipe;

import java.math.BigDecimal;
import java.util.UUID;

/** The list/browse shape (E2-S2, E2-S7): enough to show and filter, not the full ingredient list. */
public record RecipeSummary(
		UUID id,
		String name,
		String categoryName,
		boolean fastingCompatible,
		BigDecimal baseYieldQty,
		String baseYieldUnit,
		String status,
		boolean sattvicOverridden) {
}
