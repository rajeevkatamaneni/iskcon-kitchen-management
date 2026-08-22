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

		/** The verbatim yield, the per-head portion, and the rest of what a recipe book carries. */
		String yieldNote,
		BigDecimal perHeadQty,
		String perHeadUnit,
		String subtitle,
		String badge,
		BigDecimal indicativeCost,
		String why,
		String cateringNote,
		String subRegion,
		String noteStart,
		String noteVessel,
		String noteSeason,
		List<String> tags,
		List<String> serveWith,

		/** The library recipe this was copied from, or null where it was written here. */
		java.util.UUID masterRecipeId,
		String status,
		String sattvicOverrideReason,
		int version,
		List<RecipeIngredientView> ingredients,
		Instant createdAt) {
}
