package org.iskcon.kms.recipe;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The full recipe, for detail and editing. */
public record RecipeView(
		UUID id,
		String name,
		UUID categoryId,
		String categoryName,
		boolean fastingCompatible,
		BigDecimal baseYieldQty,
		String baseYieldUnit,
		String method,
		String notes,
		String regionTag,
		String status,
		String sattvicOverrideReason,
		int version,
		List<RecipeIngredientView> ingredients,
		Instant createdAt) {
}
