package org.iskcon.kms.meal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.inventory.CommittedStockService;
import org.iskcon.kms.inventory.InventoryUnits;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingredient sufficiency for planned meals (E4-S5), judged against what the store still has for a
 * dish once <em>the rest of the saved plan</em> has been accounted for (T-088).
 *
 * <h2>What the badge used to mean, and why it was wrong</h2>
 *
 * <p>The allocation below has always been a running one: it walks meals in the order the store will
 * be drawn down in and subtracts as it goes, so two meals can never both read "sufficient" against
 * one sack of rice. What it did <em>not</em> control was the set of meals it walked — it walked
 * exactly the range it was asked about. The day screen asks about a single day
 * ({@code mealSufficiency(date, date)}), so opening Thursday on its own showed Thursday the whole
 * sack: Monday to Wednesday were never in the question, and their claims were never subtracted.
 * The same Thursday read <em>short</em> from the week view and <em>ready</em> from its own page, and
 * neither answer was a property of Thursday.
 *
 * <p>So the walk no longer depends on the question. It always runs over the whole of the window the
 * temple is buying for — the same set of claims {@code CommittedStockService} sums into the
 * inventory screen's <em>committed</em> column — and the {@code from}/{@code to} arguments now only
 * choose which of those meals to <em>report</em>. Ask about one day, a week or the fortnight and
 * each meal comes back with the same answer.
 *
 * <h2>The comparison, and the trap it has to avoid</h2>
 *
 * <p><em>Available</em> on the inventory screen is on hand minus <strong>every</strong> in-horizon
 * claim — and a planned meal is one of those claims. Measure a meal against that figure directly and
 * every meal reports short by exactly its own size, because its own claim has already been taken out
 * of the number it is being compared to. That error looks entirely plausible on screen.
 *
 * <p>What a meal is judged against here is therefore <strong>on hand minus the claims of every other
 * planned meal that reaches the pot before it</strong> — which is what the running walk computes, a
 * meal never being ahead of itself. Two consequences worth stating because they are the cases that
 * get this wrong:
 *
 * <ul>
 *   <li><strong>Six days each needing 10 kg against 50 kg in the store: five read ready and the
 *       sixth reads short</strong>, whichever day you open. The alternative — subtracting every
 *       <em>other</em> meal's claim symmetrically, ahead or behind — turns all six amber, because
 *       each of them is individually unaffordable once the other five are paid for. That is true in
 *       aggregate and useless on a day screen: it reports six problems where the temple has one, and
 *       it cannot say which day the sack actually runs out on. The queue is the cooking order, which
 *       is the order the planner already shows.</li>
 *   <li><strong>The same recipe planned on two days excludes only itself in each case</strong>, for
 *       free: the two are separate rows in the walk, keyed by meal-plan id, so the later one is
 *       charged for the earlier and the earlier is charged for neither.</li>
 * </ul>
 *
 * <h2>A meal the window does not cover</h2>
 *
 * <p>A dish outside the buying window is claimed by nobody — including itself — and this service
 * answers {@link SufficiencyStatus#PLANNING} for it rather than inventing a comparison. Two kinds
 * of dish land there and the same reasoning covers both: one <em>beyond</em> the horizon will be
 * cooked from rice nobody has bought yet, so measuring it against today's shelf would leave a
 * Janmashtami plan permanently red for no reason a person could act on; and one still {@code
 * PLANNED} in the <em>past</em> is a recording gap (T-087), not a claim on the shelf. As a festival
 * comes inside the horizon its claim appears, which is exactly when the shopping list starts buying
 * for it, and the badge and the shopping list then agree because they read one window.
 *
 * <p>A <em>recorded</em> meal is not spoken for at all: {@code status = 'PLANNED'} bounds both the
 * walk and the report, so a cooked dish appears in neither. Its stock has already moved through the
 * ledger, the planner badges it "Cooked" from the dish's own status, and a second opinion here would
 * be one that subtracted the same rice twice.
 *
 * <h2>Where the window is defined</h2>
 *
 * <p>In one place: {@code CommittedStockService}. This service used to carry its own copy of the
 * fourteen-day/thirty-day pair, deliberately left duplicated by T-086 for this task to collapse.
 * It is gone — the window arrives here as the set of claims itself, so the badge and the inventory
 * column cannot drift apart even by one day's worth of festival.
 */
@Service
public class SufficiencyService {

	private final JdbcTemplate jdbc;
	private final CommittedStockService committedStock;

	public SufficiencyService(JdbcTemplate jdbc, CommittedStockService committedStock) {
		this.jdbc = jdbc;
		this.committedStock = committedStock;
	}

	/**
	 * Sufficiency for every planned meal between two dates.
	 *
	 * <p>The dates select what is reported, never what is counted: the allocation behind this runs
	 * over the whole buying window either way.
	 */
	@Transactional(readOnly = true)
	public List<MealSufficiency> sufficiency(LocalDate from, LocalDate to) {
		Map<UUID, List<IngredientShortfall>> allocated = allocateAcrossWindow();

		List<MealSufficiency> out = new ArrayList<>();
		for (MealRow meal : loadPlannedMeals(from, to)) {
			// Absent from the allocation means one of two things and the same answer serves both:
			// the dish sits outside the window the temple is buying for, or its recipe names no
			// ingredients at all. In neither case is there a stock claim to assess.
			List<IngredientShortfall> shortfalls = allocated.get(meal.id());
			SufficiencyStatus status;
			if (shortfalls == null) {
				status = SufficiencyStatus.PLANNING;
				shortfalls = List.of();
			} else {
				status = shortfalls.isEmpty() ? SufficiencyStatus.SUFFICIENT : SufficiencyStatus.SHORT;
			}
			out.add(new MealSufficiency(meal.id(), meal.planDate(), meal.mealKind(), meal.readyBy(),
					meal.recipeName(), status, shortfalls));
		}
		return out;
	}

	/**
	 * The aggregated shortfall across the ordering horizon. This is what E5-S2 turns into purchase
	 * orders, and it is the same walk the badge reads — one shortage, reported twice, never computed
	 * twice.
	 */
	@Transactional(readOnly = true)
	public List<ShortfallItem> shortfallFeed() {
		Map<UUID, ShortfallItem> byIngredient = new LinkedHashMap<>();
		for (List<IngredientShortfall> shortfalls : allocateAcrossWindow().values()) {
			for (IngredientShortfall s : shortfalls) {
				byIngredient.merge(s.ingredientId(),
						new ShortfallItem(s.ingredientId(), s.ingredientName(), s.shortBy(), s.unit()),
						(a, b) -> new ShortfallItem(a.ingredientId(), a.ingredientName(),
								a.shortBy().add(b.shortBy()), a.unit()));
			}
		}
		return new ArrayList<>(byIngredient.values());
	}

	// ---------------------------------------------------------------------

	/**
	 * Allocates the store to every claim in the buying window, in the order the store is drawn down,
	 * and returns each meal's unmet ingredients — an empty list where the meal is covered.
	 *
	 * <p>A meal is keyed by its plan id rather than by its recipe because the same recipe on two days
	 * is two claims, and each has to be charged for the other one only if it is ahead of it. A meal
	 * with no claim at all is simply absent, and {@link #sufficiency} reads absent as "nothing to
	 * assess".
	 */
	private Map<UUID, List<IngredientShortfall>> allocateAcrossWindow() {
		Map<UUID, BigDecimal> remaining = onHandBaseByIngredient();
		Map<UUID, IngRef> refs = ingredientRefs();

		Map<UUID, List<IngredientShortfall>> out = new LinkedHashMap<>();
		for (CommittedStockService.MealClaim claim : committedStock.claimsInHorizon()) {
			List<IngredientShortfall> shortfalls = new ArrayList<>();

			for (Map.Entry<UUID, BigDecimal> req : claim.requirementsBase().entrySet()) {
				UUID ing = req.getKey();
				BigDecimal needBase = req.getValue();
				BigDecimal availBase = remaining.getOrDefault(ing, BigDecimal.ZERO);
				IngRef ref = refs.getOrDefault(ing, new IngRef("(unknown)", Unit.KG));

				if (availBase.compareTo(needBase) >= 0) {
					remaining.put(ing, availBase.subtract(needBase));
				} else {
					// A meal short of an ingredient still takes what there is: the rice does not stay
					// on the shelf for the day after just because today could not be cooked in full.
					remaining.put(ing, BigDecimal.ZERO);
					// All four values are data, not display. The three figures are exact and in the
					// ingredient's own unit because the ordering pipeline buys against them, and
					// rounding here would round what the temple actually purchases. The unit beside
					// them stays the stored name for the same reason: a field called `unit` carries
					// the enum everywhere else in this API (StockItemView, ScaledLine.rawUnit), and
					// only a field that says `displayUnit` carries a label. Turning this one into
					// "Kg" would make it neither clean data nor a finished sentence, and the display
					// rule already lives where display belongs.
					shortfalls.add(new IngredientShortfall(ing, ref.name(),
							InventoryUnits.fromBase(needBase, ref.unit()),
							InventoryUnits.fromBase(availBase, ref.unit()),
							InventoryUnits.fromBase(needBase.subtract(availBase), ref.unit()),
							ref.unit().name()));
				}
			}
			out.put(claim.mealPlanId(), shortfalls);
		}
		return out;
	}

	/**
	 * What the store room holds, per ingredient, in base units.
	 *
	 * <p><strong>{@code to_on_hand_qty}, never {@code to_base_qty} (V116, T-122)</strong> — the same
	 * function every other on-hand sum in the application asks, so the planner's badge and the
	 * inventory screen cannot come to disagree about how much rice there is. A
	 * {@code USED_BEYOND_RECORDED_STOCK} row records that a meal was cooked with more than the books
	 * held; it is a discrepancy for somebody to chase, not stock that left the shelf, and it counts
	 * as zero here.
	 *
	 * <p>What the walk above does with this figure is unchanged, and the negative it can still
	 * produce is a different one: a meal's <em>remaining</em> stock falls below zero when the plans
	 * claim more than the temple holds, which is the sentence this report exists to say.
	 */
	private Map<UUID, BigDecimal> onHandBaseByIngredient() {
		Map<UUID, BigDecimal> map = new LinkedHashMap<>();
		jdbc.query("""
				SELECT ingredient_id,
					   SUM(to_on_hand_qty(quantity, unit, movement_type)) AS base
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

	/**
	 * The meals the caller asked to be told about — the report set, not the allocation set.
	 *
	 * <p>{@code status = 'PLANNED'} here is the same exclusion the walk makes: a recorded meal's
	 * stock has already moved through the ledger and the planner badges it from its own status.
	 */
	private List<MealRow> loadPlannedMeals(LocalDate from, LocalDate to) {
		return jdbc.query("""
				SELECT mp.id, mp.plan_date, mp.meal_kind, mp.ready_by, r.name AS recipe_name
				FROM meal_plans mp
				JOIN recipes r ON r.id = mp.recipe_id
				WHERE mp.status = 'PLANNED' AND mp.plan_date BETWEEN ? AND ?
				ORDER BY mp.plan_date, mp.ready_by, mp.created_at
				""", (rs, n) -> new MealRow(
				rs.getObject("id", UUID.class),
				rs.getObject("plan_date", LocalDate.class),
				rs.getString("meal_kind"),
				rs.getObject("ready_by", java.time.LocalTime.class),
				rs.getString("recipe_name")), from, to);
	}

	private record MealRow(
			UUID id, LocalDate planDate, String mealKind, java.time.LocalTime readyBy, String recipeName) {
	}

	private record IngRef(String name, Unit unit) {
	}
}
