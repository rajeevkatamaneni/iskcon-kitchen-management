package org.iskcon.kms.costing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a day's planned food costs, estimated from vendors' last-known prices (B2, E3-S8).
 *
 * <p><strong>This is the final version, not a stepping stone.</strong> The reasoning that shapes the
 * figure — that it is an honest estimate rather than a false exact one, that the ingredients it
 * cannot price are named rather than absorbed, and that labour is deliberately absent — now lives
 * with the calculation in {@link BasketCostingService}, because it governs every cost figure in the
 * application and not only this one.
 *
 * <p>What is left here is the question this service asks: <em>what is today's food costing us</em>.
 * It gathers the day's dishes into one basket and prices it. E3-S9 asks a different question of the
 * same data — what a public-prasadam plate costs against a Sunday feast plate — and asks it through
 * the same code, keyed by meal kind instead of thrown into a single pot.
 */
@Service
public class MaterialsCostService {

	private final JdbcTemplate jdbc;
	private final BasketCostingService costing;

	public MaterialsCostService(JdbcTemplate jdbc, BasketCostingService costing) {
		this.jdbc = jdbc;
		this.costing = costing;
	}

	@Transactional(readOnly = true)
	public MaterialsCost costFor(LocalDate date) {
		IngredientBasket basket = new IngredientBasket();
		for (MealRow meal : mealsOn(date)) {
			basket.addAll(costing.scaledBasket(meal.recipeId(), meal.targetYield()));
		}

		CostedBasket costed = costing.cost(basket);
		return new MaterialsCost(date, costed.estimatedTotal(), costed.ingredientsPriced(),
				costed.ingredientsWithoutPrice(), costed.unpriced());
	}

	// ---------------------------------------------------------------------

	/**
	 * The day's dishes, cancelled ones excluded.
	 *
	 * <p>Note what this is <em>not</em>: {@code status = 'PLANNED'}, which is what sufficiency filters
	 * on and would be wrong here. Marking a meal cooked moves it out of PLANNED, so a tile built on
	 * that filter would show the day's cost falling away hour by hour as the kitchen worked, reaching
	 * zero by the evening. The question is what today's food costs, and a meal that has been cooked
	 * still cost what it cost. Only a cancelled meal is genuinely not part of the day's bill — which
	 * is also how a dish marked "not made" at the stove leaves the figure, since recording one moves
	 * it to CANCELLED.
	 */
	private List<MealRow> mealsOn(LocalDate date) {
		// The date is the meal's day (D-27): a dish no longer carries a date of its own.
		return jdbc.query("""
				SELECT d.recipe_id, d.target_yield
				FROM meal_dishes d
				JOIN meals m ON m.id = d.meal_id
				JOIN meal_plan_days pd ON pd.id = m.meal_plan_day_id
				JOIN recipes r ON r.id = d.recipe_id
				WHERE d.status <> 'CANCELLED' AND pd.plan_date = ?
				ORDER BY m.ready_by, d.created_at, d.id
				""", (rs, n) -> new MealRow(
				rs.getObject("recipe_id", UUID.class),
				rs.getBigDecimal("target_yield")), date);
	}

	private record MealRow(UUID recipeId, BigDecimal targetYield) {
	}
}
