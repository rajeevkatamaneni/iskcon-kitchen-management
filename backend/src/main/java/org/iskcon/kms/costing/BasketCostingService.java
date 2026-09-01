package org.iskcon.kms.costing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.inventory.InventoryUnits;
import org.iskcon.kms.recipe.RecipeService;
import org.iskcon.kms.recipe.ScaledLine;
import org.iskcon.kms.recipe.ScaledRecipeView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scaled ingredient baskets, and what they are worth (E3-S8 D5, extracted by E3-S9).
 *
 * <p>This is the one code path behind every materials-cost figure in the application. It was pulled
 * out of {@code MaterialsCostService}, which had built a basket per meal and then thrown the split
 * away to return a single daily total: the day's figure is now this class's answer to a basket
 * covering the day, and the per-meal-kind report is the same answer to a basket per kind. One
 * calculation, several questions — which is what E3-S8 D5 asked for when it recorded the
 * duplication against {@code SufficiencyService} as a debt.
 *
 * <p>Deliberately split in two halves that know nothing of each other. {@link #scaledBasket} turns a
 * planned dish into ingredients; {@link #cost} turns any basket into money. A caller costing
 * something that never came from a recipe — issues off the stock ledger, say — builds its own
 * {@link IngredientBasket} and uses the second half alone.
 *
 * <p><strong>The estimate is honest about what it is</strong> (E3-S8 D1, unchanged). A true cost
 * would need inventory valuation — which batch each spoonful came out of and what that batch was
 * paid for. The store room will not support it, and not because code is missing: a great deal of
 * what it holds was <em>donated</em>, and a gift in kind has an estimated value and no purchase price
 * at all. An estimate that says it is an estimate is the more truthful of the two.
 *
 * <p><strong>Labour is not here</strong> (E3-S8 D4, unchanged). A cook on a 6am–2pm shift is making
 * breakfast <em>and</em> lunch, so their pay can only ever be allocated across the meals their hours
 * overlap, never measured. Whatever split were chosen would be an assumption presented as a figure.
 */
@Service
public class BasketCostingService {

	private final JdbcTemplate jdbc;
	private final RecipeService recipeService;

	public BasketCostingService(JdbcTemplate jdbc, RecipeService recipeService) {
		this.jdbc = jdbc;
		this.recipeService = recipeService;
	}

	/**
	 * What one planned dish needs: its recipe scaled to the yield the plan asks for.
	 *
	 * <p>The same scaling {@code SufficiencyService} does when it asks whether the store room can
	 * cover the plan. The two questions differ in what they do with the basket afterwards, not in how
	 * it is built.
	 */
	@Transactional(readOnly = true)
	public IngredientBasket scaledBasket(UUID recipeId, BigDecimal targetYield) {
		IngredientBasket basket = new IngredientBasket();
		ScaledRecipeView scaled = recipeService.scale(recipeId, targetYield);
		for (ScaledLine line : scaled.ingredients()) {
			basket.add(line.ingredientId(), line.rawQuantity(), Unit.valueOf(line.rawUnit()));
		}
		return basket;
	}

	/**
	 * What a basket costs at vendors' last-known prices, and which of its ingredients the figure does
	 * not cover.
	 *
	 * <p>An empty basket costs a rounded zero and names nothing — a day with nothing planned reports
	 * nothing, rather than a zero that reads as a statement about the food.
	 */
	@Transactional(readOnly = true)
	public CostedBasket cost(IngredientBasket basket) {
		if (basket.isEmpty()) {
			return new CostedBasket(BigDecimal.ZERO.setScale(2), 0, List.of());
		}

		Map<UUID, PricedIngredient> catalogue = catalogue(List.copyOf(basket.ingredientIds()));

		BigDecimal total = BigDecimal.ZERO;
		int priced = 0;
		List<UnpricedIngredient> unpriced = new ArrayList<>();

		for (UUID ingredientId : basket.ingredientIds()) {
			PricedIngredient ref = catalogue.get(ingredientId);
			if (ref == null) {
				// An ingredient the recipe references but the catalogue no longer shows. Foreign keys
				// make this all but impossible; counting it beats dropping it if it ever happens.
				unpriced.add(new UnpricedIngredient(ingredientId, "(unknown ingredient)", null, null));
				continue;
			}

			Set<Unit.Family> families = basket.familiesUsed(ingredientId);
			boolean convertible = families.size() == 1 && families.contains(ref.unit().family());
			BigDecimal quantity =
					convertible ? InventoryUnits.fromBase(basket.baseQuantity(ingredientId), ref.unit()) : null;

			// Two ways to fail to price something, and they read the same to the person looking at the
			// figure: nobody has recorded a price, or the basket measures the ingredient in a family its
			// catalogue unit cannot express (a litre of milk against a unit of Kg). The second is a
			// data fault worth fixing, but guessing a density here would put a wrong number on a
			// screen, and an honest gap beats a wrong number.
			if (!convertible || ref.price() == null) {
				unpriced.add(new UnpricedIngredient(
						ingredientId, ref.name(), quantity, convertible ? ref.unit().name() : null));
				continue;
			}

			total = total.add(quantity.multiply(ref.price()));
			priced++;
		}

		return new CostedBasket(total.setScale(2, RoundingMode.HALF_UP), priced, List.copyOf(unpriced));
	}

	// ---------------------------------------------------------------------

	/**
	 * Each ingredient's name, catalogue unit and the price this estimate will use.
	 *
	 * <p><strong>Which vendor's price.</strong> The preferred vendor's, where the temple has named one
	 * — that is the whole point of naming one, and it is what the shopping-list suggestions and
	 * generated purchase orders already buy at. A partial unique index guarantees at most one
	 * preferred vendor per ingredient, so the subquery cannot return two rows.
	 *
	 * <p>Where no vendor is preferred, the fallback is the <em>dearest</em> price on record rather
	 * than the cheapest or an average. The temple has expressed no view about whom it buys this from,
	 * so any of those prices might be the one it pays; taking the highest means the estimate errs
	 * towards overstating the bill rather than understating it, and a food budget that turns out to be
	 * quietly too small is the more damaging of the two mistakes. The average was rejected for a
	 * plainer reason: it is a price no vendor actually charges.
	 *
	 * <p><strong>What the price is per.</strong> {@code vendor_supplies.last_price} carries no unit of
	 * its own. It is taken to be rupees per one of the ingredient's own catalogue unit — ₹45 per Kg
	 * for an ingredient held in Kg — which is how the rest of the system already reads it: the same
	 * figure is copied into {@code purchase_order_lines.expected_price} beside a quantity in the
	 * ingredient's canonical unit, and invoice variance multiplies the two together. Anything else
	 * would make those existing figures wrong too.
	 *
	 * <p>Receiving now holds to that reading rather than weakening it: a storekeeper enters what the
	 * bill says per the receipt line's own unit, and {@code ReceivingService} converts to the
	 * ingredient's canonical unit before writing {@code last_price} (INV1). So the figure this query
	 * reads is a price somebody actually paid, expressed in exactly the unit this method assumes.
	 */
	private Map<UUID, PricedIngredient> catalogue(List<UUID> ingredientIds) {
		String placeholders = String.join(", ", Collections.nCopies(ingredientIds.size(), "?"));
		Map<UUID, PricedIngredient> out = new LinkedHashMap<>();
		jdbc.query("""
				SELECT i.id, i.name, i.canonical_unit,
					   COALESCE(
						   (SELECT vs.last_price FROM vendor_supplies vs
							 WHERE vs.ingredient_id = i.id AND vs.preferred AND vs.last_price IS NOT NULL),
						   (SELECT max(vs.last_price) FROM vendor_supplies vs
							 WHERE vs.ingredient_id = i.id AND vs.last_price IS NOT NULL)) AS price
				FROM ingredients i
				WHERE i.id IN (""" + placeholders + ")",
				(rs) -> {
					out.put(rs.getObject("id", UUID.class), new PricedIngredient(
							rs.getString("name"),
							Unit.valueOf(rs.getString("canonical_unit")),
							rs.getBigDecimal("price")));
				}, ingredientIds.toArray());
		return out;
	}

	private record PricedIngredient(String name, Unit unit, BigDecimal price) {
	}
}
