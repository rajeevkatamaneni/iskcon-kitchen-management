package org.iskcon.kms.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Draw the ingredients for cooking a recipe at a given scale (E3-S6). The same shape serves the
 * preview (which reads {@code recipeId}, {@code targetYield}, {@code batchOverrides}) and the commit
 * (which additionally links the movements to a meal plan).
 *
 * @param recipeId       the recipe being cooked
 * @param targetYield    how much to make, in the recipe's own yield unit
 * @param mealPlanId     the meal plan this cooking belongs to (E4), or null for ad-hoc cooking
 * @param batchOverrides optional per-ingredient batch choices; the rest follow FEFO
 * @param note           free-text context stored on each consumption movement
 */
public record ConsumeRequest(
		@NotNull UUID recipeId,
		@NotNull @Positive BigDecimal targetYield,
		UUID mealPlanId,
		@Valid List<BatchOverride> batchOverrides,
		@Size(max = 500) String note) {
}
