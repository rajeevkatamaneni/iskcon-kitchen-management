package org.iskcon.kms.ingredientrequest;

import java.util.List;

/**
 * One whole request: its heading, what it asks for, what the kitchen says it is cooking, and
 * everything that has happened to it.
 *
 * <p>The heading is the same {@link IngredientRequestSummary} the list shows, embedded rather than
 * copied field by field. Two records carrying the same fifteen columns are two places to forget to
 * add the sixteenth.
 */
public record IngredientRequestView(
		IngredientRequestSummary request,
		List<IngredientRequestLineView> lines,
		List<IngredientRequestDishView> dishes,
		List<IngredientRequestEventView> events) {
}
