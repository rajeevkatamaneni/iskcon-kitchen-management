package org.iskcon.kms.costing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * What a day's planned food costs, estimated from vendors' last-known prices (B2).
 *
 * <p><strong>This is the final version, not a stepping stone.</strong> A true cost would need
 * inventory valuation — knowing which batch each spoonful came out of and what that batch was paid
 * for. The store room will not support it, and not because the code is missing: a great deal of what
 * it holds was <em>donated</em>. A gift in kind has an estimated value and no purchase price at all,
 * so the moment it is cooked a "perfect" figure becomes part fiction dressed as accounting. An
 * estimate that says it is an estimate is the more truthful of the two, and it is cheap.
 *
 * <p><strong>The gap is reported, never absorbed.</strong> {@code last_price} is maintained by hand —
 * {@code VendorService.setSupply} is its only writer, and nothing in receiving, invoicing or goods
 * receipts writes a price back — so an ingredient nobody has priced has no price here either. Such
 * an ingredient is counted and named rather than silently costed at zero: a total that quietly omits
 * a third of the basket is worse than one that admits the hole, because only the second can be
 * acted on.
 *
 * <p><strong>Labour is deliberately absent.</strong> It is not a matter of data — the weekly template
 * says who works which hours, and a monthly salary gives a day rate. It is that a cook on a 6am–2pm
 * shift is making breakfast <em>and</em> lunch, so their pay can only ever be <em>allocated</em>
 * across the meals their hours overlap, never measured. Whatever split were chosen would be an
 * assumption presented as a figure, and this build declines to make it.
 *
 * <p><strong>The basket computation is knowingly duplicated.</strong> {@code SufficiencyService} in
 * the meal package already scales planned meals into per-ingredient quantities, but every part of it
 * is private, and that package is being changed under other work. Rather than widen an API across a
 * moving boundary, the read-only queries are repeated here; a later pass should extract the shared
 * "scaled ingredient basket for a date range" and have both call it.
 */
@Service
public class MaterialsCostService {

	private final JdbcTemplate jdbc;
	private final RecipeService recipeService;

	public MaterialsCostService(JdbcTemplate jdbc, RecipeService recipeService) {
		this.jdbc = jdbc;
		this.recipeService = recipeService;
	}

	@Transactional(readOnly = true)
	public MaterialsCost costFor(LocalDate date) {
		Map<UUID, BigDecimal> basketBase = new LinkedHashMap<>();
		Map<UUID, EnumSet<Unit.Family>> familiesUsed = new LinkedHashMap<>();

		for (MealRow meal : mealsOn(date)) {
			ScaledRecipeView scaled = recipeService.scale(meal.recipeId(), meal.targetServings());
			for (ScaledLine line : scaled.ingredients()) {
				// The merge matters: a recipe may list the same ingredient on two lines — ghee in the
				// tempering and ghee in the finish — and it is bought once.
				Unit unit = Unit.valueOf(line.rawUnit());
				basketBase.merge(line.ingredientId(),
						InventoryUnits.toBase(line.rawQuantity(), unit), BigDecimal::add);
				familiesUsed.computeIfAbsent(line.ingredientId(), k -> EnumSet.noneOf(Unit.Family.class))
						.add(unit.family());
			}
		}

		if (basketBase.isEmpty()) {
			return new MaterialsCost(date, BigDecimal.ZERO.setScale(2), 0, 0, List.of());
		}

		Map<UUID, PricedIngredient> catalogue = catalogue(List.copyOf(basketBase.keySet()));

		BigDecimal total = BigDecimal.ZERO;
		int priced = 0;
		List<UnpricedIngredient> unpriced = new ArrayList<>();

		for (Map.Entry<UUID, BigDecimal> entry : basketBase.entrySet()) {
			UUID ingredientId = entry.getKey();
			PricedIngredient ref = catalogue.get(ingredientId);
			if (ref == null) {
				// An ingredient the recipe references but the catalogue no longer shows. Foreign keys
				// make this all but impossible; counting it beats dropping it if it ever happens.
				unpriced.add(new UnpricedIngredient(ingredientId, "(unknown ingredient)", null, null));
				continue;
			}

			EnumSet<Unit.Family> families = familiesUsed.get(ingredientId);
			boolean convertible = families.size() == 1 && families.contains(ref.unit().family());
			BigDecimal quantity = convertible ? InventoryUnits.fromBase(entry.getValue(), ref.unit()) : null;

			// Two ways to fail to price something, and they read the same to the person looking at the
			// tile: nobody has recorded a price, or the recipe measures the ingredient in a family its
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

		return new MaterialsCost(date, total.setScale(2, RoundingMode.HALF_UP), priced, unpriced.size(),
				List.copyOf(unpriced));
	}

	// ---------------------------------------------------------------------

	/**
	 * The day's meals, cancelled ones excluded.
	 *
	 * <p>Note what this is <em>not</em>: {@code status = 'PLANNED'}, which is what sufficiency filters
	 * on and would be wrong here. Marking a meal cooked moves it out of PLANNED, so a tile built on
	 * that filter would show the day's cost falling away hour by hour as the kitchen worked, reaching
	 * zero by the evening. The question is what today's food costs, and a meal that has been cooked
	 * still cost what it cost. Only a cancelled meal is genuinely not part of the day's bill.
	 */
	private List<MealRow> mealsOn(LocalDate date) {
		return jdbc.query("""
				SELECT mp.recipe_id, mp.target_servings
				FROM meal_plans mp
				JOIN recipes r ON r.id = mp.recipe_id
				WHERE mp.status <> 'CANCELLED' AND mp.plan_date = ?
				ORDER BY mp.ready_by, mp.created_at
				""", (rs, n) -> new MealRow(
				rs.getObject("recipe_id", UUID.class),
				rs.getBigDecimal("target_servings")), date);
	}

	/**
	 * Each ingredient's name, catalogue unit and the price this estimate will use.
	 *
	 * <p><strong>Which vendor's price.</strong> The preferred vendor's, where the temple has named one
	 * — that is the whole point of naming one, and it is what the order-list suggestions and generated
	 * purchase orders already buy at. A partial unique index guarantees at most one preferred vendor
	 * per ingredient, so the subquery cannot return two rows.
	 *
	 * <p>Where no vendor is preferred, the fallback is the <em>dearest</em> price on record rather
	 * than the cheapest or an average. The temple has expressed no view about whom it buys this from,
	 * so any of those prices might be the one it pays; taking the highest means the estimate errs
	 * towards overstating the day's bill rather than understating it, and a food budget that turns out
	 * to be quietly too small is the more damaging of the two mistakes. The average was rejected for a
	 * plainer reason: it is a price no vendor actually charges.
	 *
	 * <p><strong>What the price is per.</strong> {@code vendor_supplies.last_price} carries no unit of
	 * its own. It is taken to be rupees per one of the ingredient's own catalogue unit — ₹45 per Kg
	 * for an ingredient held in Kg — which is how the rest of the system already reads it: the same
	 * figure is copied into {@code purchase_order_lines.expected_price} beside a quantity in the
	 * ingredient's canonical unit, and invoice variance multiplies the two together. Anything else
	 * would make those existing figures wrong too.
	 */
	private Map<UUID, PricedIngredient> catalogue(List<UUID> ingredientIds) {
		String placeholders = String.join(", ", java.util.Collections.nCopies(ingredientIds.size(), "?"));
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

	private record MealRow(UUID recipeId, BigDecimal targetServings) {
	}

	private record PricedIngredient(String name, Unit unit, BigDecimal price) {
	}
}
