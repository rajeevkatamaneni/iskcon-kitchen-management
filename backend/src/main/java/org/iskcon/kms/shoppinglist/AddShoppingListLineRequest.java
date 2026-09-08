package org.iskcon.kms.shoppinglist;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A line added to the shopping list by hand (T-027) — something the cook knows is needed that no
 * demand stream suggested. The regenerator computes lines from a meal-plan shortfall, a stock
 * threshold and an outstanding purchase order; none of those knows that the gas is nearly out or
 * that a festival needs flowers, and until now there was nowhere on this screen to say so.
 *
 * <p><strong>There is deliberately no {@code unit}.</strong> The line is written in the
 * ingredient's own {@code canonical_unit}, exactly as regeneration writes it
 * ({@code ShoppingListService.regenerateForCurrentTenant}, reading {@code ingredientRefs()}). A
 * caller free to pick a different one is how a list ends up asking a vendor for five litres of
 * rice: the column's CHECK admits all five unit names, so nothing downstream would have refused it.
 * Deriving the unit rather than accepting it means there is no unit to get wrong.
 *
 * <p>{@code suggestedVendorId} is optional and, when absent, the ingredient's preferred vendor is
 * suggested — the same answer regeneration would have given. It is here at all because the request
 * shape has to admit a vendor for the day this screen offers one; no screen sends it today.
 */
public record AddShoppingListLineRequest(
		@NotNull UUID ingredientId,

		/**
		 * How much to buy, in the ingredient's canonical unit.
		 *
		 * <p>{@code @Positive} rather than {@code @PositiveOrZero}, matching
		 * {@code shopping_list_lines_qty_positive} (V25:38, renamed V81:37-38). A zero is not a way
		 * to park a placeholder on the list: a line that asks for none of something tells the person
		 * carrying the list to the market nothing, and would reach the database only to be refused
		 * by a constraint whose message nobody can read.
		 */
		@NotNull @Positive BigDecimal suggestedQty,

		UUID suggestedVendorId) {
}
