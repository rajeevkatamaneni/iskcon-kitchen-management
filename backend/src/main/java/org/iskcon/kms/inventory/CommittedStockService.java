package org.iskcon.kms.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.occasion.OccasionService;
import org.iskcon.kms.occasion.ResolvedOccasion;
import org.iskcon.kms.recipe.RecipeService;
import org.iskcon.kms.recipe.ScaledLine;
import org.iskcon.kms.recipe.ScaledRecipeView;
import org.iskcon.kms.tenancy.TempleClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the saved plan intends to draw out of the store, per ingredient (T-086).
 *
 * <p>Inventory used to show one figure — on hand — and judge <em>Low</em> against it. That reads
 * 415 kg of ash gourd as <em>Fine</em> while 410 kg of it is already promised to Sunday's feast,
 * which is precisely the moment the screen was supposed to be useful. So there are three figures
 * now: <strong>on hand</strong>, a physical fact that only a movement changes; <strong>committed</strong>,
 * computed here; and <strong>available = on hand − committed</strong>, which is derived and displayed
 * and <strong>never stored</strong>. Storing it would give the temple a fourth number that can
 * disagree with the other three, and the whole reason stock is summed from the ledger rather than
 * kept in a column is that a stored figure eventually lies.
 *
 * <h2>What counts as committed, and why each exclusion is there</h2>
 *
 * <p><strong>Only rows still {@code PLANNED}.</strong> This is the double-subtraction guard and it
 * is the one that would be quietly wrong. A meal that has been recorded is {@code COOKED}, and
 * recording it posted {@code CONSUMPTION} movements — so the rice has <em>already left</em> the
 * on-hand figure. Counting the plan as well would subtract the same rice twice and show a temple a
 * shortage it does not have. A dish called off at the stove ({@code notMade}) and a plan cancelled
 * before cooking are both {@code CANCELLED}, and neither will ever draw anything.
 *
 * <p><strong>Only from the temple's today forward.</strong> A plan still sitting at {@code PLANNED}
 * with a date in the past has not drawn its stock, so counting it would not double-subtract — but it
 * is a <em>recording</em> gap rather than a claim on the shelf, and it never resolves itself. Left
 * in, every day nobody records would shave availability again, permanently, until the numbers on
 * this screen meant nothing. The gap it represents is a real problem and it is T-087's, not this
 * column's.
 *
 * <p><strong>Only within the ordering horizon.</strong> The horizon is not a new idea invented here:
 * it is the one the shopping list already buys against — fourteen days, extended to reach any
 * festival falling within thirty ({@link #BASE_HORIZON_DAYS}, {@link #FESTIVAL_LOOKAHEAD_DAYS} — the
 * pair the shopping list's shortfall feed and the planner's badge both read, through
 * {@link #claimsInHorizon()}). The argument for bounding it at all is
 * that <em>available</em> means "what is left of what is on the shelf, for the meals that will
 * actually draw on it". A Janmashtami plan three months out will be cooked from rice nobody has
 * bought yet; subtracting it from today's sack says "you are short" when the truthful answer is "you
 * will buy it", and it would leave every staple in the temple permanently red. As that festival
 * comes within thirty days its claim appears — which is exactly when the shopping list starts buying
 * for it, and the two screens agree because they are reading the same window.
 *
 * <p><strong>That pair of constants now lives here and nowhere else</strong> (T-088). T-086 left a
 * second copy in {@code SufficiencyService} deliberately, for this task to collapse; the planner
 * badge no longer computes a window at all, because it reads the claims themselves through
 * {@link #claimsInHorizon()}. So the badge on the planner and the <em>committed</em> column on
 * inventory cannot disagree about which meals count — not by a day of festival lookahead, and not
 * by an exclusion added to one and forgotten in the other. Anything else that comes to need this
 * window should read it from here too rather than restating fourteen and thirty.
 */
@Service
public class CommittedStockService {

	/** How far ahead the temple buys as a matter of routine. */
	private static final int BASE_HORIZON_DAYS = 14;

	/** How far ahead a festival is allowed to pull the horizon out. */
	private static final int FESTIVAL_LOOKAHEAD_DAYS = 30;

	private final TempleClock clock;
	private final JdbcTemplate jdbc;
	private final RecipeService recipeService;
	private final OccasionService occasionService;

	public CommittedStockService(
			JdbcTemplate jdbc, RecipeService recipeService, OccasionService occasionService, TempleClock clock) {
		this.clock = clock;
		this.jdbc = jdbc;
		this.recipeService = recipeService;
		this.occasionService = occasionService;
	}

	/**
	 * Committed quantity per ingredient, in base units, for every ingredient the plan claims.
	 *
	 * <p>Keyed by ingredient rather than by inventory item because a claim is made against an
	 * ingredient: a recipe names Rice, not the shelf Rice sits on. An ingredient with no claim is
	 * simply absent, and callers read absent as zero.
	 */
	@Transactional(readOnly = true)
	public Map<UUID, BigDecimal> committedBaseByIngredient() {
		Map<UUID, BigDecimal> totals = new LinkedHashMap<>();
		for (Claim claim : claims()) {
			totals.merge(claim.ingredientId(), claim.quantityBase(), BigDecimal::add);
		}
		return totals;
	}

	/**
	 * The meals that claimed one ingredient's stock, in the order they will be cooked.
	 *
	 * <p>Planning order — date, then the time the food is due — because that is the order the store
	 * will actually be drawn down in, and because the first row is then the one a shortage bites
	 * first.
	 */
	@Transactional(readOnly = true)
	public List<CommittedMeal> committedFor(UUID ingredientId, Unit canonicalUnit) {
		List<CommittedMeal> out = new ArrayList<>();
		for (Claim claim : claims()) {
			if (!claim.ingredientId().equals(ingredientId)) {
				continue;
			}
			out.add(new CommittedMeal(
					claim.mealPlanId(), claim.planDate(), claim.mealKind(), claim.eventName(),
					claim.recipeName(), InventoryUnits.fromBase(claim.quantityBase(), canonicalUnit),
					canonicalUnit.name()));
		}
		return out;
	}

	/**
	 * The plan's claims inside the horizon, one entry per dish, in the order the store will be drawn
	 * down — date, then the time the food is due.
	 *
	 * <p>This is the same set of claims {@link #committedBaseByIngredient()} sums, handed over
	 * un-summed so that the planner's sufficiency badge can allocate stock to them one dish at a time
	 * (T-088). Two callers, one definition of what the plan claims and one definition of the window
	 * it claims within: a dish that is committed on the inventory screen is a dish the badge counts,
	 * necessarily rather than by agreement.
	 *
	 * <p>A dish whose recipe names no ingredients is absent rather than present-and-empty. There is
	 * no claim to make for it, and the caller reads absent as "nothing to assess".
	 */
	@Transactional(readOnly = true)
	public List<MealClaim> claimsInHorizon() {
		Map<UUID, Map<UUID, BigDecimal>> byMeal = new LinkedHashMap<>();
		for (Claim claim : claims()) {
			byMeal.computeIfAbsent(claim.mealPlanId(), k -> new LinkedHashMap<>())
					.merge(claim.ingredientId(), claim.quantityBase(), BigDecimal::add);
		}

		List<MealClaim> out = new ArrayList<>();
		for (Map.Entry<UUID, Map<UUID, BigDecimal>> e : byMeal.entrySet()) {
			out.add(new MealClaim(e.getKey(), e.getValue()));
		}
		return out;
	}

	// ---------------------------------------------------------------------

	/**
	 * Every claim the saved plan makes inside the horizon, one row per dish per ingredient.
	 *
	 * <p>Scaling is memoised on the recipe and the yield together, not on the recipe alone: a temple
	 * cooks the same khichadi at the same 100 kg most days of the week, so the same scale is asked
	 * for repeatedly, and two plans of the same recipe at different yields are genuinely different
	 * answers. Without it a fortnight of three meals a day is forty-odd recipe reads for one page.
	 */
	private List<Claim> claims() {
		LocalDate today = LocalDate.now(clock.zone());
		LocalDate horizon = orderingHorizonEnd(today);

		Map<ScaleKey, ScaledRecipeView> scaled = new LinkedHashMap<>();
		List<Claim> claims = new ArrayList<>();

		for (PlannedDish dish : plannedDishes(today, horizon)) {
			ScaledRecipeView recipe = scaled.computeIfAbsent(
					new ScaleKey(dish.recipeId(), dish.targetYield()),
					key -> recipeService.scale(key.recipeId(), key.targetYield()));

			// A recipe is allowed to name one ingredient on two lines — a tempering of the same
			// cumin as the body of the dish — and the claim is what the dish draws in total, so the
			// lines are merged before they become rows. Left unmerged the detail page would show the
			// same meal twice for one ingredient and invite somebody to add them up a second time.
			Map<UUID, BigDecimal> perIngredient = new LinkedHashMap<>();
			for (ScaledLine line : recipe.ingredients()) {
				perIngredient.merge(line.ingredientId(),
						InventoryUnits.toBase(line.rawQuantity(), Unit.valueOf(line.rawUnit())),
						BigDecimal::add);
			}

			for (Map.Entry<UUID, BigDecimal> e : perIngredient.entrySet()) {
				claims.add(new Claim(dish.id(), dish.planDate(), dish.mealKind(), dish.eventName(),
						dish.recipeName(), e.getKey(), e.getValue()));
			}
		}
		return claims;
	}

	/**
	 * The last day the plan is treated as a claim on stock already in the store.
	 *
	 * <p>Fourteen days, pushed out to the furthest festival resolving within thirty. Deliberately the
	 * festival's own date rather than a flat thirty: what is being said is "we are already buying for
	 * that day", and the days between inherit that because they will be shopped for in the same trip.
	 */
	private LocalDate orderingHorizonEnd(LocalDate today) {
		LocalDate to = today.plusDays(BASE_HORIZON_DAYS);
		for (ResolvedOccasion o : occasionService.resolve(today, today.plusDays(FESTIVAL_LOOKAHEAD_DAYS))) {
			if (o.date().isAfter(to)) {
				to = o.date();
			}
		}
		return to;
	}

	/**
	 * The dishes still intending to be cooked in the window.
	 *
	 * <p>{@code status = 'PLANNED'} is the whole of the double-subtraction guard, and RLS is the
	 * whole of the tenant scoping — this query names no tenant because it must not: the policy on
	 * {@code meal_plans} answers that from the verified token, and a predicate here would be a second
	 * opinion about it.
	 */
	private List<PlannedDish> plannedDishes(LocalDate from, LocalDate to) {
		return jdbc.query("""
				SELECT mp.id, mp.plan_date, mp.meal_kind, mp.event_name, mp.recipe_id,
					   r.name AS recipe_name, mp.target_yield
				FROM meal_plans mp
				JOIN recipes r ON r.id = mp.recipe_id
				WHERE mp.status = 'PLANNED' AND mp.plan_date BETWEEN ? AND ?
				ORDER BY mp.plan_date, mp.ready_by, mp.created_at
				""", (rs, n) -> new PlannedDish(
				rs.getObject("id", UUID.class),
				rs.getObject("plan_date", LocalDate.class),
				rs.getString("meal_kind"),
				rs.getString("event_name"),
				rs.getObject("recipe_id", UUID.class),
				rs.getString("recipe_name"),
				rs.getBigDecimal("target_yield")), from, to);
	}

	/**
	 * One dish's whole claim on the store: every ingredient it draws, in base units, with a recipe's
	 * repeated lines already merged.
	 *
	 * <p>Public because {@link #claimsInHorizon()} hands it out, and nested rather than given its own
	 * file because it means nothing on its own: it is a position in that list, and the position — the
	 * order the dishes reach the pot — is half of what it says.
	 */
	public record MealClaim(UUID mealPlanId, Map<UUID, BigDecimal> requirementsBase) {
	}

	private record PlannedDish(
			UUID id, LocalDate planDate, String mealKind, String eventName, UUID recipeId,
			String recipeName, BigDecimal targetYield) {
	}

	private record Claim(
			UUID mealPlanId, LocalDate planDate, String mealKind, String eventName, String recipeName,
			UUID ingredientId, BigDecimal quantityBase) {
	}

	/** A recipe scaled to a yield. Both halves matter, so both are in the key. */
	private record ScaleKey(UUID recipeId, BigDecimal targetYield) {
	}
}
