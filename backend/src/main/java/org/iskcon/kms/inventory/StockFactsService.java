package org.iskcon.kms.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.tenancy.TempleClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three facts the stock screen gained in T-432 — <em>last counted</em>, <em>on order</em> and
 * roughly <em>how long it lasts</em> — each computed for the whole temple in one statement.
 *
 * <p><strong>Why they are here and not on {@link InventoryItemService}.</strong> That class is
 * already the largest thing in this package and its subject is one sentence: stock is the sum of
 * the ledger. These three are not sums of the ledger — one is a date out of it, one is arithmetic
 * over purchase orders, and one is a rate measured over a period — and each carries a rule that has
 * to be written down beside its query rather than buried among the stock arithmetic.
 *
 * <p><strong>Aggregate, never per row.</strong> Every method here returns a map keyed by ingredient
 * and is asked once for a whole screen, exactly as
 * {@link CommittedStockService#committedBaseByIngredient()} is. 114 consumables times three new
 * facts is 342 statements if any one of them is asked per row, and
 * {@code ShoppingListStatementCountIT} exists because a per-row read crept back in once before. The
 * {@code ingredientId} argument each takes is a <em>narrowing</em> for the single-item screen, in
 * the shape {@code InventoryItemService.loadBatches} already uses — not a per-row lookup.
 *
 * <p><strong>Everything is tenant-scoped by the database.</strong> Nothing here names a tenant:
 * every table read is under RLS on a connection whose {@code app.tenant_id} came from the verified
 * token, so a temple can only ever be told about its own orders and its own ledger.
 */
@Service
public class StockFactsService {

	/**
	 * How far back the consumption rate looks.
	 *
	 * <p>Bounded for the reason {@code IssuedFromStoreService.MAX_PERIOD_DAYS} is bounded — an
	 * unbounded scan of the ledger grows with the temple — and set at a quarter rather than a year
	 * because a temple's cooking changes with the season. Rice in Shravana is not rice in March, and
	 * an average taken over both describes neither.
	 */
	static final int HISTORY_DAYS = 90;

	/**
	 * The two halves of "enough history to judge", and the numbers are the whole of the honesty
	 * requirement (Rajeev, 2026-09-20).
	 *
	 * <p><strong>Six separate days, spread over at least a fortnight.</strong> Days rather than
	 * movements, because one meal writes one movement per lot it draws from: a single Sunday feast
	 * that emptied three batches of rice is three rows and one day's evidence, and counting rows
	 * would let it masquerade as a habit. Six days over a fortnight is about every other day for two
	 * weeks, which is the least that distinguishes a pattern from a coincidence; Rajeev's own test of
	 * the idea was that "an item consumed twice in a month is not a trend", and this refuses that
	 * case and everything weaker than it.
	 *
	 * <p><strong>Both, not either.</strong> Six days inside one week is a festival, not a rate. A
	 * fortnight holding two days is two draws. Each number alone admits a case the other exists to
	 * refuse.
	 *
	 * <p>Measured on the seeded temple the day this was written: 52 of its 114 consumables clear
	 * both bars and 62 do not, which is the shape a real answer should have — most of a new temple's
	 * catalogue has no pattern yet, and saying so is the point.
	 */
	static final int MIN_DAYS_USED = 6;

	/** See {@link #MIN_DAYS_USED}. */
	static final int MIN_SPAN_DAYS = 14;

	/**
	 * The furthest ahead the estimate will speak.
	 *
	 * <p>Ninety days of evidence divided into a shelf holding four years of stock produces a number,
	 * and the number is not knowledge — it is the same division carried far past anything that was
	 * observed. Past this the answer becomes "more than three months", which is both true and all
	 * that can be said.
	 */
	static final int COVER_CAP_DAYS = 90;

	/**
	 * The movement kinds that count as the store being drawn down.
	 *
	 * <p><strong>{@code CONSUMPTION}</strong> is the kitchen cooking with it, and is the bulk of it.
	 *
	 * <p><strong>{@code ISSUE}</strong> is stock sent to a child kitchen (V77). It left this store
	 * and will not come back, so as far as this shelf is concerned it was used. Leaving it out would
	 * tell a temple that supplies a satellite kitchen that its rice lasts far longer than it does.
	 *
	 * <p><strong>{@code USED_BEYOND_RECORDED_STOCK} is <em>not</em> counted, and the reasoning is
	 * worth recording because the measurement points the other way.</strong> The obvious argument for
	 * leaving it out is that it moves no stock — V116 made {@code to_on_hand_qty} return zero for it,
	 * which is why it is absent from every balance in this package — and that counting it would
	 * double-count. That argument is wrong, and it was checked rather than assumed:
	 * {@code InventoryConsumptionService.bookShortfall} writes the row for <em>the part of one
	 * requirement the batches could not cover</em>, in addition to the draws that covered the rest,
	 * never instead of them. On the seeded temple 82 (dish, ingredient) pairs carry both a
	 * {@code CONSUMPTION} row and a {@code USED_BEYOND_RECORDED_STOCK} row, and their sum is what the
	 * recipe actually asked for. So adding the two would double-count nothing, and leaving the second
	 * out under-states how fast a temple gets through exactly the ingredients it is shortest of.
	 *
	 * <p><strong>What settles it is the rule this codebase already keeps about summing this table,
	 * not the arithmetic.</strong> {@code StockMovementLedgerIT.everyLedgerSumGoesThroughTheOnHandFunction}
	 * forbids any file that reads {@code stock_movements} from calling {@code to_base_qty} — the
	 * exclusion lives in {@code to_on_hand_qty} and lives there once, because T-122 was the case of
	 * six readers of one ledger disagreeing about how much rice there is. Counting a shortfall here
	 * means summing the ledger some other way, and a second way of summing this table is the defect
	 * that guard exists to stop, whatever the second way is for. A rate that is a little conservative
	 * is a smaller cost than that.
	 *
	 * <p>It errs towards saying stock lasts <em>longer</em> than it will for an ingredient the temple
	 * has been cooking beyond its books, which is the direction worth knowing about; on the data we
	 * have, those items read "None left" anyway, because a temple that cooks past its books is
	 * holding nothing. Changing it means changing that guard, which is a decision for Rajeev.
	 */
	private static final String DRAW_TYPES = "'CONSUMPTION', 'ISSUE'";

	private final JdbcTemplate jdbc;
	private final TempleClock clock;

	public StockFactsService(JdbcTemplate jdbc, TempleClock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	// ---- Last counted ----------------------------------------------------

	/**
	 * When somebody last stood in front of the shelf and counted it, per ingredient.
	 *
	 * <p><strong>What counts as a count.</strong> An {@code ADJUSTMENT} carrying
	 * {@code COUNT_CORRECTION}, whose own enum says what it means: "a stock-take found the ledger and
	 * the shelf disagreed; this reconciles them". Spoilage, damage and waste are not counts — they
	 * are things that happened to the stock — so a temple that writes off a spoiled sack has still
	 * not counted the shelf, and this goes on saying so.
	 *
	 * <p><strong>A compensating correction is excluded, and it had to be said out loud.</strong> The
	 * automatic reversal {@code StockMovementService.compensate} writes is <em>also</em> an
	 * {@code ADJUSTMENT} carrying {@code COUNT_CORRECTION}, marked {@code reference_type =
	 * 'CORRECTION'}. Nobody counted anything: the application put back what a mistaken movement took.
	 * Counting it would reset "last counted" to the day somebody fixed a typo about last Tuesday's
	 * khichadi.
	 *
	 * <p><strong>The opening count is included, deliberately.</strong> It is an
	 * {@code ADJUSTMENT}/{@code COUNT_CORRECTION} too, written by {@code InventoryItemService.create}
	 * with the note "Opening count, when the item was added to inventory." It is a person standing in
	 * the store with a set of scales — the {@code create} path asks the same question in the same
	 * words as the item screen's own adjustment form — so it is exactly what this column is for.
	 * Excluding it would tell every temple that has just started tracking that it has never counted
	 * anything, on the very day it counted everything.
	 *
	 * <p><strong>A count that was itself reversed still counts as a count.</strong> The date says
	 * when somebody went and looked, and somebody did; the figure they wrote being wrong is what the
	 * reversal is about.
	 *
	 * @param ingredientId one ingredient, or null for every one the temple has moved
	 */
	@Transactional(readOnly = true)
	public Map<UUID, LocalDate> lastCountedByIngredient(UUID ingredientId) {
		String sql = """
				SELECT m.ingredient_id, MAX((m.created_at AT TIME ZONE ?)::date) AS last_counted
				FROM stock_movements m
				WHERE m.movement_type = 'ADJUSTMENT'
				  AND m.reason_category = 'COUNT_CORRECTION'
				  AND m.reference_type IS DISTINCT FROM 'CORRECTION'
				""";
		Map<UUID, LocalDate> byIngredient = new LinkedHashMap<>();
		List<Object> args = new ArrayList<>();
		// Read once. TempleClock.zone() is a SELECT against `tenants`, so a second mention of it in
		// one method is a second statement for the same answer.
		args.add(clock.zone().getId());
		if (ingredientId != null) {
			sql += " AND m.ingredient_id = ?";
			args.add(ingredientId);
		}
		jdbc.query(sql + " GROUP BY m.ingredient_id", rs -> {
			byIngredient.put(
					rs.getObject("ingredient_id", UUID.class),
					rs.getObject("last_counted", LocalDate.class));
		}, args.toArray());
		return byIngredient;
	}

	// ---- On order --------------------------------------------------------

	/**
	 * What a vendor has been asked for and not yet delivered, per ingredient, in base units.
	 *
	 * <p>Rajeev, 2026-09-20, called this "very important", and it is the fact the screen was missing
	 * that most often makes a low-stock warning the wrong thing to act on: a storekeeper looking at
	 * four kilos of rice needs to know whether two hundred are on their way before they go and buy
	 * more.
	 *
	 * <h3>A draft is not on order</h3>
	 *
	 * <p><strong>{@code DRAFT} is excluded and the exclusion is the whole decision here.</strong> A
	 * draft has not been sent to anybody. Nothing is coming, nobody has promised anything, and a
	 * column that says "200 Kg on order" because somebody started typing an order this morning is
	 * telling a lie that stops a temple buying rice it needs. Two places in this codebase already
	 * point the same way: {@code VendorPerformanceService} holds
	 * {@code LIVE_ORDER = "po.status NOT IN ('DRAFT', 'CANCELLED')"}, and the donor-facing page once
	 * summed purchase-order lines with no status filter at all so that drafts counted as money spent
	 * — a defect no test caught, because the fixture that fed it also left the status to the column
	 * default and so was wrong in exactly the way the query was.
	 *
	 * <p><strong>It is deliberately a different answer from {@code ShoppingListService}'s.</strong>
	 * That class's {@code ingredientsOnLiveOrders()} <em>does</em> include {@code DRAFT}, and rightly:
	 * it is deciding whether to suggest buying something again, and re-suggesting what a colleague is
	 * in the middle of drafting is a nag. Suppressing a suggestion and stating a fact are different
	 * questions and they get different answers. This one is a fact.
	 *
	 * <p><strong>{@code RECEIVED} and {@code CLOSED} are out for a simpler reason:</strong> the first
	 * has arrived and the second has ended. What a closed order still owes is
	 * {@code ShoppingListService.poOutstandingByIngredient()}'s subject — it re-feeds the remainder
	 * onto the shopping list — and it is not on its way here, so it is not on order.
	 *
	 * <p><strong>Lines naming something the catalogue has never heard of are excluded</strong>
	 * ({@code ingredient_id IS NULL}), for the reason T-024 gives one query along: a null is a
	 * perfectly valid map key, so four plastic stools and an extension cord would otherwise collapse
	 * into one bucket and be handed to whichever consumable asked the map about itself.
	 *
	 * <p>Rejected goods are not received, so they stay outstanding and go on being counted here —
	 * which is right: the vendor still owes them.
	 *
	 * @param ingredientId one ingredient, or null for every one on a live order
	 */
	@Transactional(readOnly = true)
	public Map<UUID, BigDecimal> onOrderBaseByIngredient(UUID ingredientId) {
		// A line at a time, converted and merged in Java rather than summed in SQL, exactly as
		// ShoppingListService.poOutstandingByIngredient does the same arithmetic over the same two
		// tables. Two lines of one order may be in different units of one family — a sack in Kg and
		// a top-up in gm — so they cannot be added before they are converted, and InventoryUnits is
		// the one conversion (BaseQuantityIT holds every caller to it).
		String sql = """
				SELECT pol.ingredient_id, pol.unit,
					   pol.quantity - COALESCE(r.received, 0) AS outstanding
				FROM purchase_order_lines pol
				JOIN purchase_orders po ON po.id = pol.po_id
				LEFT JOIN (
					SELECT po_line_id, SUM(received_qty) AS received
					FROM goods_receipt_lines GROUP BY po_line_id
				) r ON r.po_line_id = pol.id
				WHERE po.status IN ('SENT', 'PARTIALLY_RECEIVED')
				  AND pol.ingredient_id IS NOT NULL
				  AND pol.quantity - COALESCE(r.received, 0) > 0
				""";
		Map<UUID, BigDecimal> byIngredient = new LinkedHashMap<>();
		List<Object> args = new ArrayList<>();
		if (ingredientId != null) {
			sql += " AND pol.ingredient_id = ?";
			args.add(ingredientId);
		}
		jdbc.query(sql, rs -> {
			BigDecimal outstanding = rs.getBigDecimal("outstanding");
			if (outstanding == null || outstanding.signum() <= 0) {
				return;
			}
			BigDecimal base = InventoryUnits.toBase(outstanding, Unit.valueOf(rs.getString("unit")));
			byIngredient.merge(rs.getObject("ingredient_id", UUID.class), base, BigDecimal::add);
		}, args.toArray());
		return byIngredient;
	}

	// ---- How fast it goes ------------------------------------------------

	/**
	 * How much of each ingredient leaves the store on an average day, in base units — and only for
	 * the ingredients there is enough history to say it about.
	 *
	 * <p><strong>An ingredient absent from this map is one the temple cannot be given an answer
	 * for.</strong> That is the honesty requirement expressed as a shape: there is no field saying
	 * "we are not sure", because a figure that carries a confidence flag is a figure somebody
	 * eventually reads without the flag. The rule is applied once, here, and every reader of it —
	 * the list, the item screen, anything added later — is honest by construction. See
	 * {@link #MIN_DAYS_USED} for the two bars and why each exists.
	 *
	 * <h3>Which day a draw happened on</h3>
	 *
	 * <p>Not {@code created_at} alone. A meal's draws are written when the meal is recorded as
	 * cooked, which is usually the day it was cooked and sometimes several days later — and the day
	 * the food left the store is the day the meal belongs to, not the day the paperwork caught up.
	 * So a movement that references a planned dish is dated by that dish's own day, exactly as the
	 * planner and every other screen date it (D-27), and anything else — an issue to a child kitchen
	 * — is dated by when it was recorded, because that <em>is</em> when it happened.
	 *
	 * <p>It is not a nicety. Measured on the seeded temple: every row in {@code stock_movements} has
	 * a {@code created_at} of the day the seed ran, while the meals they belong to span 2026-08-29 to
	 * 2026-09-18. Dating by {@code created_at} would have compressed three weeks of cooking into one
	 * day and reported every consumable as running out this afternoon.
	 *
	 * <p>A draw dated later than today — which nothing should write, and a clock or a plan can —
	 * is pulled back to today rather than allowed to stretch the period into the future.
	 *
	 * <h3>The arithmetic</h3>
	 *
	 * <p>Total drawn in the window, divided by the days from the <strong>first</strong> draw in the
	 * window to today inclusive. The denominator is elapsed time and not the days that had a draw:
	 * a day nobody cooked with something is still a day it lasted, and dividing by the busy days only
	 * would describe how much goes out when it goes out rather than how fast the shelf empties.
	 *
	 * <p>From the first draw rather than from the start of the window, so that an ingredient the
	 * temple started using a month ago is measured over the month it has been used and not over a
	 * quarter of which two thirds predate it.
	 *
	 * <p>A corrected movement is left out, through the same {@code NOT EXISTS} on
	 * {@code reference_type = 'CORRECTION'} that {@code IssuedFromStoreService} uses: the ledger is
	 * append-only, so a mistake is undone by a reversal pointing back at it, and counting a draw
	 * somebody has since said never happened would inflate the rate for ever.
	 *
	 * @param ingredientId one ingredient, or null for every one with a history
	 */
	@Transactional(readOnly = true)
	public Map<UUID, BigDecimal> dailyUseBaseByIngredient(UUID ingredientId) {
		// One read of the temple's clock, not two: it is a SELECT against `tenants`.
		ZoneId zone = clock.zone();
		LocalDate today = LocalDate.now(zone);
		LocalDate windowStart = today.minusDays(HISTORY_DAYS);

		String sql = """
				WITH draws AS (
					SELECT m.ingredient_id,
						   LEAST(COALESCE(d.plan_date, (m.created_at AT TIME ZONE ?)::date), ?) AS used_on,
						   ABS(to_on_hand_qty(m.quantity, m.unit, m.movement_type)) AS base
					FROM stock_movements m
					LEFT JOIN meal_dishes md
						   ON md.id = m.reference_id AND m.reference_type = 'MEAL_PLAN'
					LEFT JOIN meals ml ON ml.id = md.meal_id
					LEFT JOIN meal_plan_days d ON d.id = ml.meal_plan_day_id
					WHERE m.movement_type IN (%s)
					  AND m.quantity < 0
					  AND NOT EXISTS (
						  SELECT 1 FROM stock_movements c
						   WHERE c.reference_type = 'CORRECTION' AND c.reference_id = m.id)
				)
				SELECT ingredient_id,
					   COUNT(DISTINCT used_on) AS days_used,
					   MIN(used_on)            AS first_used,
					   SUM(base)               AS total_base
				FROM draws
				WHERE used_on >= ?
				""".formatted(DRAW_TYPES);

		Map<UUID, BigDecimal> byIngredient = new LinkedHashMap<>();
		List<Object> args = new ArrayList<>();
		args.add(zone.getId());
		args.add(today);
		args.add(windowStart);
		if (ingredientId != null) {
			sql += " AND ingredient_id = ?";
			args.add(ingredientId);
		}
		jdbc.query(sql + " GROUP BY ingredient_id", rs -> {
			long daysUsed = rs.getLong("days_used");
			LocalDate firstUsed = rs.getObject("first_used", LocalDate.class);
			BigDecimal total = rs.getBigDecimal("total_base");
			if (firstUsed == null || total == null || total.signum() <= 0) {
				return;
			}
			// Inclusive of both ends: a first draw yesterday and today is two days of observation,
			// not one. The off-by-one matters at the bar — a fortnight is fourteen days, and
			// counting thirteen would admit a case MIN_SPAN_DAYS was written to refuse.
			long span = ChronoUnit.DAYS.between(firstUsed, today) + 1;
			if (daysUsed < MIN_DAYS_USED || span < MIN_SPAN_DAYS) {
				return;
			}
			byIngredient.put(
					rs.getObject("ingredient_id", UUID.class),
					total.divide(BigDecimal.valueOf(span), 6, RoundingMode.HALF_UP));
		}, args.toArray());
		return byIngredient;
	}

	/**
	 * What {@code onHandBase} of an ingredient works out to in days, at {@code perDayBase} — or null
	 * where there is no rate to divide by, which is the "we cannot tell" the screen says in words.
	 *
	 * <p>Rounded <strong>down</strong>: an estimate that says four days when the shelf holds four and
	 * a half is safe, and one that says five is the kind of reassurance that empties a store room on
	 * a feast day. Nothing on the shelf is nought days, and is left as nought rather than suppressed
	 * — "you have none" is an answer.
	 *
	 * @param canonical the unit the ingredient is held in, for the working shown on the item screen
	 */
	static StockCover coverFor(
			BigDecimal onHandBase, BigDecimal perDayBase, Unit canonical, LocalDate today) {
		if (perDayBase == null || perDayBase.signum() <= 0) {
			return null;
		}
		BigDecimal perDay = InventoryUnits.fromBase(perDayBase, canonical);
		BigDecimal onHand = onHandBase.signum() < 0 ? BigDecimal.ZERO : onHandBase;
		BigDecimal exactDays = onHand.divide(perDayBase, 0, RoundingMode.FLOOR);

		if (exactDays.compareTo(BigDecimal.valueOf(COVER_CAP_DAYS)) > 0) {
			return new StockCover(COVER_CAP_DAYS, true, null, perDay);
		}
		int days = exactDays.intValueExact();
		return new StockCover(days, false, today.plusDays(days), perDay);
	}
}
