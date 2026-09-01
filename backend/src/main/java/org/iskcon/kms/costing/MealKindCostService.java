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
 * <p><strong>What a "meal" is.</strong> A date and a kind, the pair every other screen means by it
 * (V64). One {@code meal_plans} row is one dish, so a lunch of three dishes is one meal costing the
 * sum of its three baskets — counted once in {@code meals}, and fed to one head count rather than
 * three.
 *
 * <p><strong>The servings denominator.</strong> A head count exists only where the planner recorded
 * adults, children or seniors; it is derived the way {@code ServedMealService} derives it, largest
 * dish row wins, a child at six tenths of a portion and a senior at eight. Where no dish of a meal
 * carries any of the three, that meal <em>has no head count at all</em>, and this report will not
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

		// Dishes into meals: the pair (date, kind) is the meal, and its basket is the sum of its
		// dishes'. The head count is a whole-meal fact written onto each dish row, so it is read back
		// as the largest of them — a dish added later against a changed count must not shrink the meal.
		Map<Meal, MealTotals> meals = new LinkedHashMap<>();
		for (DishRow dish : dishesIn(from, to)) {
			MealTotals totals = meals.computeIfAbsent(new Meal(dish.planDate(), dish.mealKind()),
					k -> new MealTotals());
			totals.basket.addAll(costing.scaledBasket(dish.recipeId(), dish.targetYield()));
			Integer headCount = headCountOf(dish);
			if (headCount != null && (totals.servings == null || headCount > totals.servings)) {
				totals.servings = headCount;
			}
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
				total.unpriced(), List.copyOf(rows));
	}

	// ---------------------------------------------------------------------

	private void accumulate(KindTotals kind, MealTotals meal) {
		kind.meals++;
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
	 * Every dish planned in the period, cancelled ones excluded.
	 *
	 * <p>The same filter the daily figure uses, and for the same reason: marking a meal cooked moves
	 * it out of PLANNED, and a report that filtered on PLANNED would show a month of cooking as having
	 * cost nothing. A dish called off at the stove is recorded as CANCELLED, so "not made" leaves the
	 * figure through this filter too.
	 *
	 * <p>The dish is costed at what was <em>planned</em>, not at what the returned job card said was
	 * cooked. That keeps this report and the Today tile one calculation of one thing — a period of
	 * days must add up to the days in it — and what was actually cooked against what was planned is a
	 * different report with a different name.
	 */
	private List<DishRow> dishesIn(LocalDate from, LocalDate to) {
		return jdbc.query("""
				SELECT mp.plan_date, mp.meal_kind, mp.recipe_id, mp.target_yield,
					   mp.adults, mp.children, mp.seniors
				FROM meal_plans mp
				JOIN recipes r ON r.id = mp.recipe_id
				WHERE mp.status <> 'CANCELLED' AND mp.plan_date BETWEEN ? AND ?
				ORDER BY mp.plan_date, mp.ready_by, mp.created_at
				""", (rs, n) -> new DishRow(
				rs.getObject("plan_date", LocalDate.class),
				rs.getString("meal_kind"),
				rs.getObject("recipe_id", UUID.class),
				rs.getBigDecimal("target_yield"),
				(Integer) rs.getObject("adults"),
				(Integer) rs.getObject("children"),
				(Integer) rs.getObject("seniors")), from, to);
	}

	/** The pair every screen means by "the meal". */
	private record Meal(LocalDate planDate, String mealKind) {
	}

	private record DishRow(
			LocalDate planDate, String mealKind, UUID recipeId, BigDecimal targetYield,
			Integer adults, Integer children, Integer seniors) {
	}

	private static final class MealTotals {
		private final IngredientBasket basket = new IngredientBasket();
		private Integer servings;
	}

	private final class KindTotals {
		/** Every meal of the kind — what the total column reports. */
		private final IngredientBasket all = new IngredientBasket();
		/** Only the meals somebody counted — the numerator of the per-serving figure. */
		private final IngredientBasket counted = new IngredientBasket();
		private int meals;
		private int servings;
		private int mealsWithoutServings;

		private MealKindCost asRow(String kind) {
			CostedBasket total = costing.cost(all);
			BigDecimal perServing = servings <= 0
					? null
					: costing.cost(counted).estimatedTotal()
							.divide(BigDecimal.valueOf(servings), 2, RoundingMode.HALF_UP);
			return new MealKindCost(kind, meals, servings, mealsWithoutServings,
					total.estimatedTotal(), perServing, total.ingredientsPriced(),
					total.ingredientsWithoutPrice(), total.unpriced());
		}
	}
}
