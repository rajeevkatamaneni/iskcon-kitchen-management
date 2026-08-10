package org.iskcon.kms.inventory;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The result of planning (or committing) a consumption (E3-S6): what each ingredient needs and which
 * batches supply it. On a preview {@code sufficient} tells the cook whether the meal can be cooked
 * and {@code shortfalls} itemises what's missing; on a commit {@code sufficient} is always true and
 * each draw carries the id of the movement that was written.
 */
public record ConsumptionPlan(
		UUID recipeId,
		String recipeName,
		BigDecimal targetYield,
		String yieldUnit,
		boolean sufficient,
		List<PlannedLine> lines,
		List<StockShortfall> shortfalls) {
}
