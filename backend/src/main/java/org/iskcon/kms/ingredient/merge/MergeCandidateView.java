package org.iskcon.kms.ingredient.merge;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One ingredient in a merge group, as the merge screen shows it (R-DUP-3). Mirrors
 * {@code MergeCandidateView} in {@code frontend/lib/api.ts}.
 *
 * @param unit the ingredient's canonical unit as stored ("KG"), the same form {@code IngredientView}
 *     carries it in
 * @param preparationNote the note this ingredient's recipe lines would get when it is merged away
 *     ("sour" for "Curd, sour"); null when there is none, and always null on the kept ingredient,
 *     whose own lines are not touched
 * @param onHand on-hand stock in this ingredient's own {@code unit}, summed from the ledger exactly
 *     as every other on-hand figure is ({@code to_on_hand_qty}, V116)
 */
public record MergeCandidateView(
		UUID ingredientId,
		String name,
		String unit,
		String preparationNote,
		int recipeLineCount,
		BigDecimal onHand,
		int supplyCount) {
}
