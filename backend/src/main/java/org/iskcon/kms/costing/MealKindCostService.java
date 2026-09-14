package org.iskcon.kms.costing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What each kind of meal costs, and what a serving of it costs (E3-S9).
 *
 * <p>E3-S8 D3 settled that the materials estimate was for the day and not per meal, and it was right
 * about the question it was asked: <em>what is today's food costing us</em> is a headline on the
 * morning screen, and a daily total is exactly that. The reviewers asked a different question — what
 * a public-prasadam plate costs against a Sunday feast plate — and no single daily total can answer
 * it, however it is presented. Same data, different question. The daily total is untouched; this is
 * the same estimate kept split rather than summed, over a period instead of a day.
 *
 * <p><strong>The kinds are the temple's own.</strong> {@code meal_kinds} is tenant data. Nothing here
 * names Breakfast or Lunch or Festival feast, and nothing seeds a kind: the report groups by whatever
 * the temple actually cooked in the period, so a temple that adds "Annadana" sees Annadana, and a
 * kind nobody cooked in the period does not appear as a row of dashes.
 *
 * <p><strong>What a "meal" is.</strong> A row of its own, since D-27. One dish row is one dish, so a
 * lunch of three dishes is one meal costing the sum of its three baskets — counted once in
 * {@code meals}, and fed to one head count rather than three. Before D-27 a meal here was the pair
 * (date, kind), which counted two events on one Saturday as one meal.
 *
 * <p><strong>The servings denominator.</strong> A head count exists only where the planner recorded
 * adults, children or seniors; it is derived the way {@code ServedMealService} derives it, a child at
 * six tenths of a portion and a senior at eight, from the meal's own row. Where the meal carries none
 * of the three, that meal <em>has no head count at all</em>, and this report will not
 * invent one. In particular it does not fall back to {@code target_yield}: since V69 that column
 * holds an amount of food — litres of rasam, kilos of podi, idlis — and dividing a cost by litres
 * would put a number under a column headed "cost per serving" that is not one.
 *
 * <p>Such a meal is counted in {@code meals} and in the kind's total, and left out of
 * <em>both halves</em> of the per-serving figure. Leaving it out of the denominator alone would
 * divide the whole period's cost by part of its people and overstate every plate; leaving the whole
 * kind blank would throw away the comparison the report exists for. So the per-serving figure is the
 * honest one it can compute — the meals that were counted, divided by the people they fed — and the
 * count of meals it had to leave out travels beside it, exactly as the unpriced ingredients do.
 *
 * <p><strong>What a meal is costed at: what was cooked, once anybody knows (T-212).</strong> A meal
 * whose job card has been recorded is costed at what the card says each dish came to; a meal not yet
 * recorded is costed at what was planned, because that is all anybody knows about it. Rajeev ruled it
 * ("Costing follows actuals, option 1"). The reason is agreement: recording draws the store room down
 * by what was cooked ({@code ServedMealService.record}), so a report that went on costing the plan
 * would price food the kitchen never took off the shelf, and disagree with the stock ledger about
 * the same lunch. A row over a month mixes both kinds of meal, so each row and the total carry how
 * many meals are of each, and the screen says so rather than presenting one number as one thing.
 */
@Service
public class MealKindCostService {

	/** A child eats about six tenths of a portion, a senior about eight. The temple's own arithmetic. */
	private static final BigDecimal CHILD_PORTION = new BigDecimal("0.6");
	private static final BigDecimal SENIOR_PORTION = new BigDecimal("0.8");

	/**
	 * The longest period the report will walk. It scales every dish planned in the range through its
	 * recipe, so an unbounded range is a slow page rather than an answer; a year is longer than any
	 * comparison anybody has asked for and short enough to stay a page.
	 */
	private static final int MAX_PERIOD_DAYS = 366;

	private final JdbcTemplate jdbc;
	private final BasketCostingService costing;

	public MealKindCostService(JdbcTemplate jdbc, BasketCostingService costing) {
		this.jdbc = jdbc;
		this.costing = costing;
	}

	@Transactional(readOnly = true)
	public CostByMealKind byMealKind(LocalDate from, LocalDate to) {
		if (to.isBefore(from) || from.plusDays(MAX_PERIOD_DAYS).isBefore(to)) {
			throw new ApplicationException(ErrorCode.COST_PERIOD_NOT_VALID,
					Map.of("from", from, "to", to));
		}

		// Dishes into meals: the meal's own row is the meal (D-27), and its basket is the sum of its
		// dishes'. The head count lives on that row once, so every dish of a meal reports the same one.
		Map<Meal, MealTotals> meals = new LinkedHashMap<>();
		for (DishRow dish : dishesIn(from, to)) {
			MealTotals totals = meals.computeIfAbsent(new Meal(dish.mealId(), dish.mealKind()),
					k -> new MealTotals());
			// Scaled through the recipe exactly as stock is drawn, so a recorded dish costs the same
			// ingredients the ledger took out for it, and no unit is converted here by hand.
			totals.basket.addAll(costing.scaledBasket(dish.recipeId(), dish.costedYield()));
			totals.servings = headCountOf(dish);
			// Recording is per meal, so every dish of one meal says the same thing here.
			totals.recorded = dish.recorded();
		}

		// Meals into kinds.
		Map<String, KindTotals> kinds = new LinkedHashMap<>();
		KindTotals everything = new KindTotals();
		meals.forEach((meal, totals) -> {
			accumulate(kinds.computeIfAbsent(meal.mealKind(), k -> new KindTotals()), totals);
			accumulate(everything, totals);
		});

		List<MealKindCost> rows = new ArrayList<>();
		kinds.forEach((kind, totals) -> rows.add(totals.asRow(kind)));

		// Dearest serving first, because that is the comparison the report exists to make and reading
		// it top to bottom should be the answer. A kind with no head count anywhere has no place in
		// that ordering, so it sits at the foot, by name — present, and plainly not compared.
		rows.sort(Comparator
				.comparing((MealKindCost row) -> row.costPerServing() == null)
				.thenComparing(MealKindCost::costPerServing,
						Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(MealKindCost::mealKind, String.CASE_INSENSITIVE_ORDER));

		CostedBasket total = costing.cost(everything.all);
		return new CostByMealKind(from, to, everything.meals, everything.servings,
				everything.mealsWithoutServings, total.estimatedTotal(), total.ingredientsWithoutPrice(),
				total.unpriced(), List.copyOf(rows), everything.mealsCostedAsCooked,
				everything.mealsCostedAsPlanned);
	}

	// ---------------------------------------------------------------------

	private void accumulate(KindTotals kind, MealTotals meal) {
		kind.meals++;
		if (meal.recorded) {
			kind.mealsCostedAsCooked++;
		} else {
			kind.mealsCostedAsPlanned++;
		}
		kind.all.addAll(meal.basket);
		if (meal.servings == null) {
			kind.mealsWithoutServings++;
		} else {
			kind.servings += meal.servings;
			kind.counted.addAll(meal.basket);
		}
	}

	/**
	 * How many people this dish row was planned to feed, or null where nobody said.
	 *
	 * <p>Null and zero are different answers and are kept apart: a meal recorded as feeding nobody is
	 * a meal somebody counted, and it belongs in the denominator as the zero it is.
	 */
	private static Integer headCountOf(DishRow dish) {
		if (dish.adults() == null && dish.children() == null && dish.seniors() == null) {
			return null;
		}
		return BigDecimal.valueOf(dish.adults() == null ? 0 : dish.adults())
				.add(CHILD_PORTION.multiply(BigDecimal.valueOf(dish.children() == null ? 0 : dish.children())))
				.add(SENIOR_PORTION.multiply(BigDecimal.valueOf(dish.seniors() == null ? 0 : dish.seniors())))
				.setScale(0, RoundingMode.HALF_UP)
				.intValue();
	}

	/**
	 * Every dish in the period, cancelled ones excluded, each with the amount it is to be costed at.
	 *
	 * <p>The same filter the daily figure uses, and for the same reason: marking a meal cooked moves
	 * it out of PLANNED, and a report that filtered on PLANNED would show a month of cooking as having
	 * cost nothing. A dish called off at the stove is recorded as CANCELLED, so "not made" leaves the
	 * figure through this filter too.
	 *
	 * <p><strong>The amount is what was cooked where the meal has been recorded, and what was planned
	 * where it has not</strong> (T-212). This used to cost every dish at what was planned, on the
	 * argument that it kept this report and the Today tile one calculation. They still are one
	 * calculation — {@code MaterialsCostService} applies exactly this rule — and what changed is which
	 * figure that calculation trusts. Stock already draws on what was cooked, so costing the plan made
	 * this report disagree with the store room about the same meal; now the two agree, and a
	 * correction to a recorded meal moves the cost because it moves what was cooked.
	 *
	 * <p>Recording is the switch, not a dish's status. {@code recorded_at} is set once for the whole
	 * meal, in the same transaction that writes {@code actual_servings} on every dish it cooked and
	 * refuses a dish without one; a dish it did not cook is CANCELLED and already filtered out. So a
	 * recorded meal has a cooked figure on every dish this query returns.
	 */
	private List<DishRow> dishesIn(LocalDate from, LocalDate to) {
		// The kind is grouped by its name as the temple spells it today, read through the meal's kind
		// id, so a kind renamed mid-period is one row under its new name rather than two.
		return jdbc.query("""
				SELECT d.meal_id, k.name AS meal_kind, d.recipe_id,
					   CASE WHEN m.recorded_at IS NOT NULL THEN d.actual_servings
							ELSE d.target_yield END AS costed_yield,
					   m.recorded_at IS NOT NULL AS recorded,
					   m.adults, m.children, m.seniors
				FROM meal_dishes d
				JOIN meals m ON m.id = d.meal_id
				JOIN meal_plan_days pd ON pd.id = m.meal_plan_day_id
				JOIN meal_kinds k ON k.id = m.meal_kind_id
				JOIN recipes r ON r.id = d.recipe_id
				WHERE d.status <> 'CANCELLED' AND pd.plan_date BETWEEN ? AND ?
				ORDER BY pd.plan_date, m.ready_by, d.created_at, d.id
				""", (rs, n) -> new DishRow(
				rs.getObject("meal_id", UUID.class),
				rs.getString("meal_kind"),
				rs.getObject("recipe_id", UUID.class),
				rs.getBigDecimal("costed_yield"),
				rs.getBoolean("recorded"),
				(Integer) rs.getObject("adults"),
				(Integer) rs.getObject("children"),
				(Integer) rs.getObject("seniors")), from, to);
	}

	/** One meal, by its own id (D-27), with the name of its kind to group it under. */
	private record Meal(UUID mealId, String mealKind) {
	}

	/**
	 * @param costedYield what the dish is costed at: what was cooked if its meal is recorded, else what
	 *                    was planned
	 * @param recorded    whether the dish's meal has been recorded
	 */
	private record DishRow(
			UUID mealId, String mealKind, UUID recipeId, BigDecimal costedYield, boolean recorded,
			Integer adults, Integer children, Integer seniors) {
	}

	private static final class MealTotals {
		private final IngredientBasket basket = new IngredientBasket();
		private Integer servings;
		private boolean recorded;
	}

	private final class KindTotals {
		/** Every meal of the kind — what the total column reports. */
		private final IngredientBasket all = new IngredientBasket();
		/** Only the meals somebody counted — the numerator of the per-serving figure. */
		private final IngredientBasket counted = new IngredientBasket();
		private int meals;
		private int servings;
		private int mealsWithoutServings;
		/** Meals costed at what their job card says was cooked, and those costed at the plan. */
		private int mealsCostedAsCooked;
		private int mealsCostedAsPlanned;

		private MealKindCost asRow(String kind) {
			CostedBasket total = costing.cost(all);
			BigDecimal perServing = servings <= 0
					? null
					: costing.cost(counted).estimatedTotal()
							.divide(BigDecimal.valueOf(servings), 2, RoundingMode.HALF_UP);
			return new MealKindCost(kind, meals, servings, mealsWithoutServings,
					total.estimatedTotal(), perServing, total.ingredientsPriced(),
					total.ingredientsWithoutPrice(), total.unpriced(), mealsCostedAsCooked,
					mealsCostedAsPlanned);
		}
	}
}
