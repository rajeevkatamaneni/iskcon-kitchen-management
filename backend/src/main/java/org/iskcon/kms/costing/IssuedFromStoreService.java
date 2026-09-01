package org.iskcon.kms.costing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.ingredient.Unit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the temple store issued to each kitchen, costed (E10-S13).
 *
 * <p>The reviewers asked us to call the meal category a cost centre rather than a kitchen, and that
 * asks for a rename this system has no room for: a meal kind is a category of preparation, and a
 * kitchen is a place with a door, a person in charge and a phone number. They are not two names for
 * one thing and neither is misnamed. What the request was <em>right</em> about is underneath it —
 * an issue already records which kitchen the food went to, so the attribution has been sitting in
 * the ledger since E10-S7 and nobody had asked it a question. This class is that question.
 *
 * <p><strong>No third noun.</strong> A {@code cost_centres} table beside kitchens and meal kinds
 * would have to be created, maintained and mapped, and on the first day it would map one-to-one onto
 * the kitchens. The kitchen is the cost centre. If a temple ever names a case where the two genuinely
 * differ, that is the day to build the third noun and not before (E10 D1, and this project's standing
 * rule against abstractions nobody can name a case for).
 *
 * <p><strong>Why issues are the only path for some kitchens.</strong> There is one store and two
 * doors out of it. A kitchen that plans its meals here draws {@code CONSUMPTION} and is costed
 * through what it cooked, on the cost-per-serving report. A kitchen that does not — the Deity
 * kitchen, by design — draws {@code ISSUE} and can be costed no other way. Two doors, two paths, one
 * report each.
 *
 * <p><strong>A floor, not a total</strong> (INV5). The mathajis of the Deity kitchen sometimes buy
 * things themselves, and E10 D2 accepts that on purpose: issuing takes food off the temple's books,
 * and what the kitchen does afterwards is its own business. A kitchen not running this application
 * will not record its own purchases in it. So this report knows the store's half and can never know
 * the kitchen's, and it is named for the half it knows — <em>issued from the temple store</em>. Call
 * it "the Deity kitchen's food cost" anywhere and it will be quoted as one inside a week.
 *
 * <p>The estimate carries every caveat the rest of the costing carries, because it is the same
 * calculation: {@link BasketCostingService#cost} prices this basket exactly as it prices a day of
 * planned meals. Estimated rather than exact, materials only, no labour and no utilities (E3-S8 D1
 * and D4), and the ingredients it cannot price are counted out loud rather than costed at zero.
 */
@Service
public class IssuedFromStoreService {

	/** The temple's own day. An issue at 9pm belongs to the day the storekeeper handed it over. */
	private static final ZoneId TEMPLE_TIME = ZoneId.of("Asia/Kolkata");

	/**
	 * The longest period the report will walk, and the same one the per-meal-kind report holds to.
	 * A year is longer than any comparison anybody has asked for and short enough to stay a page.
	 */
	private static final int MAX_PERIOD_DAYS = 366;

	private final JdbcTemplate jdbc;
	private final BasketCostingService costing;

	public IssuedFromStoreService(JdbcTemplate jdbc, BasketCostingService costing) {
		this.jdbc = jdbc;
		this.costing = costing;
	}

	@Transactional(readOnly = true)
	public IssuedFromStore issuedFromStore(LocalDate from, LocalDate to) {
		if (to.isBefore(from) || from.plusDays(MAX_PERIOD_DAYS).isBefore(to)) {
			throw new ApplicationException(ErrorCode.COST_PERIOD_NOT_VALID, Map.of("from", from, "to", to));
		}

		Map<UUID, KitchenTotals> kitchens = new LinkedHashMap<>();
		IngredientBasket everything = new IngredientBasket();
		Set<UUID> allRequests = new LinkedHashSet<>();

		for (IssueRow issue : issuesIn(from, to)) {
			KitchenTotals totals = kitchens.computeIfAbsent(issue.kitchenId(),
					k -> new KitchenTotals(issue.kitchen(), issue.usesMealPlanner()));
			// The ledger writes an issue as a negative quantity, because that is what it does to the
			// store's balance. What it cost is a positive amount of food, so the sign is turned here
			// and nowhere else.
			totals.basket.add(issue.ingredientId(), issue.quantity().negate(), issue.unit());
			totals.ingredients.add(issue.ingredientId());
			totals.requests.add(issue.requestId());
			everything.add(issue.ingredientId(), issue.quantity().negate(), issue.unit());
			allRequests.add(issue.requestId());
		}

		List<KitchenIssueCost> rows = new ArrayList<>();
		kitchens.forEach((id, totals) -> rows.add(totals.asRow(id)));

		// Dearest first, because the question the report is asked is which kitchen the store's food
		// is going to. Ties by name, so the order is stable between two readings of the same period.
		rows.sort(Comparator.comparing(KitchenIssueCost::estimatedTotal).reversed()
				.thenComparing(KitchenIssueCost::kitchen, String.CASE_INSENSITIVE_ORDER));

		CostedBasket total = costing.cost(everything);
		return new IssuedFromStore(from, to, allRequests.size(), total.estimatedTotal(),
				total.ingredientsWithoutPrice(), total.unpriced(), List.copyOf(rows));
	}

	// ---------------------------------------------------------------------

	/**
	 * Every issue the store made in the period, with the kitchen it went to.
	 *
	 * <p><strong>The kitchen comes from the request, not from the movement.</strong> V77 settled that
	 * deliberately: {@code reference_id} points at the request and the request carries the kitchen, so
	 * the two can never come to disagree. {@code storage_location} is not it either — that says where
	 * in the store a thing sat, not where it went.
	 *
	 * <p><strong>A corrected issue is not an issue.</strong> The ledger is append-only, so a mistake
	 * is undone by a compensating {@code ADJUSTMENT} pointing back at the original. Costing both would
	 * charge a kitchen for food it never received; costing the original alone would too. So a movement
	 * somebody has reversed is left out of the basket entirely, which is the same answer the store's
	 * own balance already gives.
	 *
	 * <p><strong>The period is the temple's own days</strong>, converted to instants here so the index
	 * on {@code (tenant_id, movement_type, created_at)} can still be used. A report that read the
	 * server's timezone would move a late evening issue into the next day for no reason a storekeeper
	 * could explain.
	 */
	private List<IssueRow> issuesIn(LocalDate from, LocalDate to) {
		OffsetDateTime start = from.atStartOfDay(TEMPLE_TIME).toOffsetDateTime();
		OffsetDateTime end = to.plusDays(1).atStartOfDay(TEMPLE_TIME).toOffsetDateTime();
		return jdbc.query("""
				SELECT r.id AS request_id, r.kitchen_id, k.name AS kitchen_name, k.uses_meal_planner,
					   m.ingredient_id, m.quantity, m.unit
				FROM stock_movements m
				JOIN ingredient_requests r ON r.id = m.reference_id
				JOIN kitchens k ON k.id = r.kitchen_id
				WHERE m.movement_type = 'ISSUE'
				  AND m.reference_type = 'INGREDIENT_REQUEST'
				  AND m.created_at >= ? AND m.created_at < ?
				  AND NOT EXISTS (
					  SELECT 1 FROM stock_movements c
					   WHERE c.reference_type = 'CORRECTION' AND c.reference_id = m.id)
				ORDER BY k.name, m.created_at
				""", (rs, n) -> new IssueRow(
				rs.getObject("request_id", UUID.class),
				rs.getObject("kitchen_id", UUID.class),
				rs.getString("kitchen_name"),
				rs.getBoolean("uses_meal_planner"),
				rs.getObject("ingredient_id", UUID.class),
				rs.getBigDecimal("quantity"),
				Unit.valueOf(rs.getString("unit"))), start, end);
	}

	private record IssueRow(
			UUID requestId, UUID kitchenId, String kitchen, boolean usesMealPlanner,
			UUID ingredientId, BigDecimal quantity, Unit unit) {
	}

	private final class KitchenTotals {

		private final String name;
		private final boolean usesMealPlanner;
		private final IngredientBasket basket = new IngredientBasket();
		private final Set<UUID> ingredients = new LinkedHashSet<>();
		private final Set<UUID> requests = new LinkedHashSet<>();

		private KitchenTotals(String name, boolean usesMealPlanner) {
			this.name = name;
			this.usesMealPlanner = usesMealPlanner;
		}

		private KitchenIssueCost asRow(UUID kitchenId) {
			CostedBasket costed = costing.cost(basket);
			return new KitchenIssueCost(kitchenId, name, usesMealPlanner, requests.size(),
					ingredients.size(), costed.estimatedTotal(), costed.ingredientsPriced(),
					costed.ingredientsWithoutPrice(), costed.unpriced());
		}
	}
}
