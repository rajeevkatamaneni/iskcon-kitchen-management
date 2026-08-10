package org.iskcon.kms.meal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.inventory.InventoryUnits;
import org.iskcon.kms.occasion.OccasionService;
import org.iskcon.kms.occasion.ResolvedOccasion;
import org.iskcon.kms.recipe.ScaledLine;
import org.iskcon.kms.recipe.ScaledRecipeView;
import org.iskcon.kms.recipe.RecipeService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingredient sufficiency for planned meals (E4-S5). A meal is SUFFICIENT only if stock covers its
 * scaled requirements <em>after</em> earlier uncooked meals in the horizon have taken their share —
 * so two meals can never both read "sufficient" against one sack of rice (the double-booking guard).
 *
 * <p>Computed on read, correctness first: it allocates current stock to meals in planning order
 * (date, then slot), and reports each meal's status and per-ingredient gap. The aggregate shortfall
 * across the horizon is the contract the ordering pipeline (E5-S2) consumes.
 */
@Service
public class SufficiencyService {

	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");
	private static final int BASE_HORIZON_DAYS = 14;
	private static final int FESTIVAL_LOOKAHEAD_DAYS = 30;

	private final JdbcTemplate jdbc;
	private final RecipeService recipeService;
	private final OccasionService occasionService;

	public SufficiencyService(
			JdbcTemplate jdbc, RecipeService recipeService, OccasionService occasionService) {
		this.jdbc = jdbc;
		this.recipeService = recipeService;
		this.occasionService = occasionService;
	}

	/** Sufficiency for every planned meal between two dates, with commitment accounting. */
	@Transactional(readOnly = true)
	public List<MealSufficiency> sufficiency(LocalDate from, LocalDate to) {
		return evaluate(loadPlannedMeals(from, to));
	}

	/**
	 * The aggregated shortfall across the ordering horizon: through 14 days, extended to cover any
	 * festival within 30. This is what E5-S2 turns into purchase orders.
	 */
	@Transactional(readOnly = true)
	public List<ShortfallItem> shortfallFeed() {
		LocalDate today = LocalDate.now(TEMPLE_ZONE);
		LocalDate to = today.plusDays(BASE_HORIZON_DAYS);
		for (ResolvedOccasion o : occasionService.resolve(today, today.plusDays(FESTIVAL_LOOKAHEAD_DAYS))) {
			if (o.date().isAfter(to)) {
				to = o.date();
			}
		}

		Map<UUID, ShortfallItem> byIngredient = new LinkedHashMap<>();
		for (MealSufficiency meal : evaluate(loadPlannedMeals(today, to))) {
			for (IngredientShortfall s : meal.shortfalls()) {
				byIngredient.merge(s.ingredientId(),
						new ShortfallItem(s.ingredientId(), s.ingredientName(), s.shortBy(), s.unit()),
						(a, b) -> new ShortfallItem(a.ingredientId(), a.ingredientName(),
								a.shortBy().add(b.shortBy()), a.unit()));
			}
		}
		return new ArrayList<>(byIngredient.values());
	}

	// ---------------------------------------------------------------------

	private List<MealSufficiency> evaluate(List<MealRow> meals) {
		Map<UUID, BigDecimal> remaining = onHandBaseByIngredient();
		Map<UUID, IngRef> refs = ingredientRefs();

		List<MealSufficiency> out = new ArrayList<>();
		for (MealRow meal : meals) {
			Map<UUID, BigDecimal> required = requirementsBase(meal.recipeId(), meal.targetServings());
			List<IngredientShortfall> shortfalls = new ArrayList<>();

			for (Map.Entry<UUID, BigDecimal> req : required.entrySet()) {
				UUID ing = req.getKey();
				BigDecimal needBase = req.getValue();
				BigDecimal availBase = remaining.getOrDefault(ing, BigDecimal.ZERO);
				IngRef ref = refs.getOrDefault(ing, new IngRef("(unknown)", Unit.KG));

				if (availBase.compareTo(needBase) >= 0) {
					remaining.put(ing, availBase.subtract(needBase));
				} else {
					remaining.put(ing, BigDecimal.ZERO);
					shortfalls.add(new IngredientShortfall(ing, ref.name(),
							InventoryUnits.fromBase(needBase, ref.unit()),
							InventoryUnits.fromBase(availBase, ref.unit()),
							InventoryUnits.fromBase(needBase.subtract(availBase), ref.unit()),
							ref.unit().name()));
				}
			}

			SufficiencyStatus status = required.isEmpty()
					? SufficiencyStatus.PLANNING
					: (shortfalls.isEmpty() ? SufficiencyStatus.SUFFICIENT : SufficiencyStatus.SHORT);
			out.add(new MealSufficiency(meal.id(), meal.planDate(), meal.slot(), meal.recipeName(),
					status, shortfalls));
		}
		return out;
	}

	private Map<UUID, BigDecimal> requirementsBase(UUID recipeId, BigDecimal targetServings) {
		ScaledRecipeView scaled = recipeService.scale(recipeId, targetServings);
		Map<UUID, BigDecimal> req = new LinkedHashMap<>();
		for (ScaledLine line : scaled.ingredients()) {
			req.merge(line.ingredientId(),
					InventoryUnits.toBase(line.rawQuantity(), Unit.valueOf(line.rawUnit())), BigDecimal::add);
		}
		return req;
	}

	private Map<UUID, BigDecimal> onHandBaseByIngredient() {
		Map<UUID, BigDecimal> map = new LinkedHashMap<>();
		jdbc.query("""
				SELECT ingredient_id,
					   SUM(quantity * CASE unit WHEN 'KG' THEN 1000 WHEN 'L' THEN 1000 ELSE 1 END) AS base
				FROM stock_movements GROUP BY ingredient_id
				""", (rs) -> {
			map.put(rs.getObject("ingredient_id", UUID.class), rs.getBigDecimal("base"));
		});
		return map;
	}

	private Map<UUID, IngRef> ingredientRefs() {
		Map<UUID, IngRef> refs = new LinkedHashMap<>();
		jdbc.query("SELECT id, name, canonical_unit FROM ingredients", (rs) -> {
			refs.put(rs.getObject("id", UUID.class),
					new IngRef(rs.getString("name"), Unit.valueOf(rs.getString("canonical_unit"))));
		});
		return refs;
	}

	private List<MealRow> loadPlannedMeals(LocalDate from, LocalDate to) {
		return jdbc.query("""
				SELECT mp.id, mp.plan_date, mp.slot, mp.recipe_id, r.name AS recipe_name, mp.target_servings
				FROM meal_plans mp
				JOIN recipes r ON r.id = mp.recipe_id
				WHERE mp.status = 'PLANNED' AND mp.plan_date BETWEEN ? AND ?
				ORDER BY mp.plan_date, mp.slot, mp.created_at
				""", (rs, n) -> new MealRow(
				rs.getObject("id", UUID.class),
				rs.getObject("plan_date", LocalDate.class),
				rs.getString("slot"),
				rs.getObject("recipe_id", UUID.class),
				rs.getString("recipe_name"),
				rs.getBigDecimal("target_servings")), from, to);
	}

	private record MealRow(
			UUID id, LocalDate planDate, String slot, UUID recipeId, String recipeName, BigDecimal targetServings) {
	}

	private record IngRef(String name, Unit unit) {
	}
}
