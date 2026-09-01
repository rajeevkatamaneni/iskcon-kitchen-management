package org.iskcon.kms.costing;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One kitchen, and what the temple store issued to it over the reported period (E10-S13).
 *
 * <p><strong>This is a floor, not a total.</strong> It is what left the temple's store against this
 * kitchen's requests, and nothing else. A kitchen that buys food itself — the Deity kitchen's
 * mathajis do, and E10 D2 accepts it on purpose — spends money this application never sees, so the
 * kitchen's real food cost is this figure plus an amount nobody here can name. Every screen, export
 * and sentence carrying this number says <em>issued from the temple store</em> and never
 * <em>this kitchen's food cost</em>.
 *
 * @param requests              how many ingredient requests the store filled for this kitchen in the
 *                             period. A request issued in two goes is still one request.
 * @param ingredients           how many distinct ingredients went over the counter to it.
 * @param usesMealPlanner       whether this kitchen plans its meals here <em>now</em>. Where it does,
 *                             its food since then leaves the store as consumption rather than as an
 *                             issue, and is costed on the cost-per-serving report instead — one
 *                             kitchen, one door (E10 D5). Anything here is from before that.
 * @param ingredientsWithoutPrice how many of the issued ingredients the estimate does not cover.
 *                             Shown wherever the total is shown (E3-S8 D2); {@code unpriced} names
 *                             them.
 */
public record KitchenIssueCost(
		UUID kitchenId,
		String kitchen,
		boolean usesMealPlanner,
		int requests,
		int ingredients,
		BigDecimal estimatedTotal,
		int ingredientsPriced,
		int ingredientsWithoutPrice,
		List<UnpricedIngredient> unpriced) {
}
