package org.iskcon.kms.shoppinglist;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
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
import org.iskcon.kms.ingredient.IngredientUnits;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.inventory.InventoryItemService;
import org.iskcon.kms.inventory.InventoryUnits;
import org.iskcon.kms.meal.ShortfallItem;
import org.iskcon.kms.meal.SufficiencyService;
import org.iskcon.kms.tenancy.TempleClock;
import org.iskcon.kms.vendor.LeadTimes;
import org.iskcon.kms.vendor.OrderUrgency;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The suggested shopping list (E5-S2): procurement that starts from data, not memory.
 *
 * <h2>It is derived, and that is the whole of T-132</h2>
 *
 * <p>Rajeev asked, on 2026-09-10: <em>"Why do we need the Regenerate shopping list button at all?
 * Why can't the shopping list auto populate every time the page loads?"</em> — and, of the ordering
 * flow as a whole, <em>"The issue starts at where the data enters the system. Without addressing
 * that, everything is a compromised fix."</em>
 *
 * <p>Until then this class held a {@code regenerateForCurrentTenant()} that upserted one suggested
 * row per ingredient and then deleted every unedited row it had not just suggested. It ran from two
 * places: the button, and a Quartz trigger at 04:30 IST. So the list already populated itself once a
 * night, and the honest answer to <em>"why the button"</em> was that neither door should exist.
 *
 * <p>Now {@link #list()} computes the suggestions on every read and writes nothing.
 * {@code shopping_list_lines} keeps only what a person decided — an edited quantity, an untick, a
 * line added by hand — and those are an <strong>overlay</strong> on whatever the read produces. A
 * decision row on its own renders nothing; the one exception is a hand-added line, which is its own
 * reason to appear because no stream will ever suggest it.
 *
 * <h2>What is suggested, and what a live order takes off the list</h2>
 *
 * <p>Three demand streams merge per ingredient: the meal-plan shortfall (E4-S5), stock below its
 * reorder level topped up to that level × a safety factor (E3-S3), and the balance a vendor never
 * brought on an order somebody has since closed (E5-S6, moved to the close by D-26). The largest of
 * the three wins, rounded up to a whole purchase unit.
 *
 * <p>Against that, D-24a and D-26: <strong>an ingredient covered by a live purchase order — draft,
 * sent, or part-delivered with a balance still owed — is not suggested at all.</strong> Rajeev's reason for choosing creation over sending as the moment
 * a line leaves: <em>"IF we take it off on send, they will be there in the shopping list begging to
 * be ordered, someone else will take pity and generate another PO. Same ingredients, 2 PO's. We
 * don't need that confusion."</em> Cancelling the order makes the line reappear, with no restore
 * path needed and none written, because the list is a function of current state and cancellation
 * changes that state.
 *
 * <p>The threshold stream used to skip any ingredient flagged sattvic-prohibited, on the reasoning
 * that such a thing could only reach the list through a recipe an admin had overridden. D-18 deleted
 * that flag on 2026-09-08, so every ingredient the temple keeps stock of is now topped up on the
 * same terms — which is the accepted consequence of the ruling, not an oversight: a temple that does
 * not stock garlic has no inventory row for it to fall below.
 */
@Service
public class ShoppingListService {

	private static final BigDecimal SAFETY_FACTOR = new BigDecimal("1.2");

	private final JdbcTemplate jdbc;
	private final SufficiencyService sufficiencyService;
	private final InventoryItemService inventoryItemService;
	private final IngredientUnits ingredientUnits;
	private final LeadTimes leadTimes;
	private final TempleClock clock;

	public ShoppingListService(
			JdbcTemplate jdbc, SufficiencyService sufficiencyService,
			InventoryItemService inventoryItemService, IngredientUnits ingredientUnits,
			LeadTimes leadTimes, TempleClock clock) {
		this.jdbc = jdbc;
		this.sufficiencyService = sufficiencyService;
		this.inventoryItemService = inventoryItemService;
		this.ingredientUnits = ingredientUnits;
		this.leadTimes = leadTimes;
		this.clock = clock;
	}

	/**
	 * The shopping list as it stands right now: the computed suggestions with this temple's own
	 * decisions laid over the top.
	 *
	 * <p>Ordered by ingredient name, as the stored version was — the sort moved from the database's
	 * {@code ORDER BY i.name} to a case-insensitive comparator here, which is the same order for
	 * every name the catalogue actually holds and no longer depends on the database's collation.
	 */
	@Transactional(readOnly = true)
	public List<ShoppingListLineView> list() {
		Map<UUID, Decision> decisions = decisions();
		return list(suggestions(handAdded(decisions)), decisions);
	}

	/**
	 * Adds a line by hand (T-027) — something the cook knows is needed that no demand stream
	 * suggested. Returns the line as the screen will render it.
	 *
	 * <p><strong>It is written {@code hand_added = true}, and that is the whole substance of this
	 * method.</strong> Every other row in this table is an overlay on a line the derivation already
	 * produced, and renders nothing on its own; nothing will ever suggest a bale of leaf plates, so
	 * without that column the line would simply not be on the list at the next page load. Before
	 * T-132 the same job was done by {@code edited = true}, which saved the row from the
	 * regenerator's delete. The column is different because the mechanism is, and
	 * {@code HandAddedLineIT} asserts the survival rather than the column, because the column is only
	 * evidence.
	 *
	 * <p><strong>A duplicate is refused, not merged.</strong> Of the two honest answers — overwrite
	 * the existing line, or say so — this says so (KMS-400131): the quantity on the existing line may
	 * be one the derivation computed or one a colleague typed, and somebody adding what they think is
	 * a new line did not ask for either to be replaced. The line they wanted is already on the screen
	 * in front of them, with a box to change.
	 *
	 * <p>The check reads the <em>derived</em> list rather than the decision table, and that is a real
	 * change: an ingredient the shortfall stream suggests has no row here at all, so a check against
	 * the table alone would let somebody add a second Rice beside the one already on their screen.
	 * {@code ON CONFLICT DO NOTHING} stays underneath it as the race guard — two people adding the
	 * same thing in the same second get one line and one clean refusal rather than a constraint
	 * violation nobody can read.
	 *
	 * <p>No dates are stored, because none ever were worth storing: nothing demanded this line, so
	 * {@link #list()} computes no needed-by and no order-by for it and the screen prints an em dash
	 * rather than a guess. That is deliberately not the same as an order-by date of today — a cook
	 * who typed in a bale of leaf plates has said nothing at all about when they are wanted, and
	 * inventing a deadline would put a red badge on a line nobody is late for.
	 */
	@Transactional
	public ShoppingListLineView addLine(AddShoppingListLineRequest request) {
		// Refuses an ingredient that does not exist. The lookup runs on the tenant-scoped
		// connection, so another temple's id is simply not found — the tenant comes from the
		// verified token by way of RLS, never from anything in this request body.
		Unit unit = ingredientUnits.canonicalUnit(request.ingredientId());

		Map<UUID, Decision> before = decisions();
		if (findIn(list(suggestions(handAdded(before)), before), request.ingredientId()) != null) {
			throw new ApplicationException(
					ErrorCode.ALREADY_ON_THE_SHOPPING_LIST, Map.of("ingredientId", request.ingredientId()));
		}

		int inserted = jdbc.update("""
				INSERT INTO shopping_list_lines (
					id, tenant_id, ingredient_id, suggested_qty, unit, included, hand_added)
				VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid,
					?, ?, ?, true, true)
				ON CONFLICT (tenant_id, ingredient_id) DO NOTHING
				""", request.ingredientId(), request.suggestedQty(), unit.name());
		if (inserted == 0) {
			throw new ApplicationException(
					ErrorCode.ALREADY_ON_THE_SHOPPING_LIST, Map.of("ingredientId", request.ingredientId()));
		}

		Map<UUID, Decision> after = decisions();
		ShoppingListLineView added =
				findIn(list(suggestions(handAdded(after)), after), request.ingredientId());
		if (added == null) {
			// Unreachable in practice — a hand-added row is on the list by construction, unless a
			// live order already covers the ingredient, and that case was refused above. Kept as a
			// refusal rather than a null so a later change to the derivation cannot hand the screen
			// a 201 with nothing in it.
			throw new ApplicationException(
					ErrorCode.RESOURCE_NOT_FOUND, Map.of("ingredientId", request.ingredientId()));
		}
		return added;
	}

	/**
	 * A human decision about a line: the quantity to buy, and whether to buy it at all.
	 *
	 * <p><strong>This is an upsert, and it has to be.</strong> Before T-132 every line on the screen
	 * had a row behind it, so an edit was an {@code UPDATE}. Now most lines have none — they are
	 * computed and gone again — so the first edit to a suggested line is what creates its decision
	 * row. The 404 is still real: it means the ingredient is not on the list at all, which is what a
	 * stale screen sends after somebody else has ordered the thing.
	 *
	 * <p><strong>A quantity is stored only when it differs from the computed one.</strong> Both
	 * callers on the screen send a quantity whatever they are doing — the tick box sends the figure
	 * it can see — so writing it through unconditionally would freeze that number on the line for
	 * ever the first time anybody unticked it, and the list would stop recomputing exactly where the
	 * person had least intended to say anything about quantity.
	 *
	 * <p>The vendor is not accepted any more. It was a column on this table until V121 and pure
	 * derivation the whole time: both writers set it from the ingredient's preferred vendor, and no
	 * screen ever sent one. What D-25 wants snapshotted lives on the purchase order —
	 * {@code purchase_orders.vendor_id}, written at creation and never derived again.
	 */
	@Transactional
	public void updateLine(UUID ingredientId, UpdateShoppingListLineRequest request) {
		Map<UUID, Decision> decisions = decisions();
		Map<UUID, Suggestion> suggestions = suggestions(handAdded(decisions));
		ShoppingListLineView line = findIn(list(suggestions, decisions), ingredientId);
		if (line == null) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("ingredientId", ingredientId));
		}
		Suggestion suggested = suggestions.get(ingredientId);
		BigDecimal computed = suggested == null ? null : suggested.quantity();
		BigDecimal override = request.suggestedQty() == null
				|| (computed != null && computed.compareTo(request.suggestedQty()) == 0)
				? null
				: request.suggestedQty();

		jdbc.update("""
				INSERT INTO shopping_list_lines (
					id, tenant_id, ingredient_id, suggested_qty, unit, included, hand_added)
				VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid,
					?, ?, ?, ?, false)
				ON CONFLICT (tenant_id, ingredient_id) DO UPDATE SET
					suggested_qty = EXCLUDED.suggested_qty,
					included = EXCLUDED.included,
					updated_at = now()
				""", ingredientId, override, line.unit(), request.included());
	}

	// ---------------------------------------------------------------------

	/**
	 * The two halves put together: a suggestion with a decision over it, a suggestion on its own, or
	 * a hand-added decision that is its own reason to be on the list.
	 */
	private List<ShoppingListLineView> list(
			Map<UUID, Suggestion> suggestions, Map<UUID, Decision> decisions) {
		LocalDate today = LocalDate.now(clock.zone());
		List<ShoppingListLineView> out = new ArrayList<>();

		for (Map.Entry<UUID, Suggestion> e : suggestions.entrySet()) {
			UUID ingredientId = e.getKey();
			Suggestion s = e.getValue();
			Decision decision = decisions.get(ingredientId);

			// The typed quantity wins over the computed one; where nobody typed one, the computed
			// figure goes on being recomputed, which is the point of storing an override only when
			// it differs. A line asking for nothing is not a line — which for a suggestion means the
			// demand has been met, and for a hand-added row cannot happen, because its quantity is
			// the number somebody typed and the column refuses a zero.
			BigDecimal qty = decision != null && decision.quantity() != null
					? decision.quantity()
					: s.quantity();
			if (qty.signum() <= 0) {
				continue;
			}

			out.add(new ShoppingListLineView(
					ingredientId,
					s.ref().name(),
					s.currentStock(),
					s.ref().unit().name(),
					qty,
					s.neededBy(),
					s.orderBy(),
					s.leadTimeDays(),
					s.orderBy() == null ? null : OrderUrgency.on(today, s.orderBy()),
					s.vendorId(),
					s.vendorName(),
					s.shortfall(),
					s.thresholdTopUp(),
					s.poOutstanding(),
					s.shortPurchaseOrders(),
					decision == null || decision.included(),
					decision != null,
					// The named mitigation for an untick that persists: a line coming back says when
					// somebody decided against it, so a stale untick announces itself the moment it
					// starts costing something. Read off updated_at, which needs no new column.
					decision != null && !decision.included() ? decision.decidedOn(clock.zone()) : null));
		}

		out.sort(Comparator.comparing(ShoppingListLineView::ingredientName, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(ShoppingListLineView::ingredientName));
		return out;
	}

	/**
	 * Everything the temple's own data says it should buy, before anybody has had an opinion about
	 * it. Nothing here is written down; this is the whole of what T-132 replaced the stored table
	 * with.
	 *
	 * <p><strong>The store room is read once, on the second line, and that one reading is what both
	 * demand streams are judged against (T-140).</strong> Until then this method summed
	 * {@code stock_movements} itself and then called two collaborators that each summed it again —
	 * three passes over a table holding every movement the temple has ever recorded, for one page
	 * load. T-139 measured that at five years of history: 146,150 rows, read three times over, on
	 * every opening of the screen.
	 *
	 * <p>The reason it is worth doing even where it were free, and the reason a cache was the wrong
	 * answer: <strong>the three readings could disagree.</strong> They sit inside one
	 * {@code @Transactional(readOnly = true)}, which is less protection than it looks like — PostgreSQL
	 * at READ COMMITTED gives each <em>statement</em> its own snapshot, so a delivery recorded between
	 * the first sum and the third would be counted by one and not the others. The shortfall would then
	 * be worked out against one figure for the rice with a different figure for the rice printed in the
	 * <em>current stock</em> column beside it: a wrong line, on a screen somebody orders food from,
	 * with nothing on it to say anything had happened. One reading cannot do that. A cache with a
	 * lifetime could do worse, which is why there is no cache here.
	 */
	private Map<UUID, Suggestion> suggestions(Set<UUID> handAddedIds) {
		Map<UUID, IngredientRef> refs = ingredientRefs();
		Map<UUID, BigDecimal> onHandBase = onHandBaseByIngredient();
		Map<UUID, LocalDate> earliestDemand = earliestDemandByIngredient();
		Map<UUID, PreferredVendor> vendors = preferredVendorsByIngredient();
		Map<UUID, Integer> recordedLeadTimes = leadTimes.recordedByIngredient();
		Set<UUID> coveredByLiveOrder = ingredientsOnLiveOrders();

		Map<UUID, Contribution> merged = new LinkedHashMap<>();

		// Stream 1: meal-plan shortfall, judged against the stock read above rather than against a
		// second reading of it (T-140).
		for (ShortfallItem s : sufficiencyService.shortfallFeed(onHandBase)) {
			merged.computeIfAbsent(s.ingredientId(), k -> new Contribution()).shortfall = s.shortBy();
		}

		// Stream 2: below-threshold stock, topped up to reorder level × safety — again against the
		// one reading.
		for (InventoryItemService.LowStockLine item : inventoryItemService.lowStock(onHandBase)) {
			IngredientRef ref = refs.get(item.ingredientId());
			if (ref == null || item.reorderThreshold() == null) {
				continue;
			}
			BigDecimal topUp = item.reorderThreshold().multiply(SAFETY_FACTOR).subtract(item.onHand());
			if (topUp.signum() > 0) {
				merged.computeIfAbsent(item.ingredientId(), k -> new Contribution()).thresholdTopUp = topUp;
			}
		}

		// Stream 3: the balance a vendor never brought on an order somebody has closed (E5-S6,
		// D-26). It re-feeds here so what was ordered but never arrived comes round again,
		// traceable to the PO that fell short. NOT while the order is live: until it is closed the
		// vendor still owes the balance and the clock is still ticking on them.
		for (Map.Entry<UUID, PoOutstanding> e : poOutstandingByIngredient().entrySet()) {
			IngredientRef ref = refs.get(e.getKey());
			if (ref == null) {
				continue;
			}
			Contribution c = merged.computeIfAbsent(e.getKey(), k -> new Contribution());
			c.poOutstanding = InventoryUnits.fromBase(e.getValue().base(), ref.unit());
			c.shortPurchaseOrders = e.getValue().poNumbers();
		}

		// A hand-added line: no stream drove it, so it contributes nothing and its quantity comes
		// from the decision row. It is seeded here rather than assembled separately so that it picks
		// up its preferred vendor and its on-hand figure from exactly the same code as every other
		// line — a cook adding jaggery still needs to be told which merchant it would be ordered
		// from, and two paths to one row is how the two come to disagree about what a line is.
		for (UUID ingredientId : handAddedIds) {
			merged.computeIfAbsent(ingredientId, k -> new Contribution());
		}

		Map<UUID, Suggestion> out = new LinkedHashMap<>();
		for (Map.Entry<UUID, Contribution> e : merged.entrySet()) {
			UUID ingredientId = e.getKey();
			IngredientRef ref = refs.get(ingredientId);
			if (ref == null) {
				continue;
			}
			// D-24a and D-26. A live order — draft, sent, or part-delivered with a balance the
			// vendor still owes — already covers this ingredient, so nothing about it
			// is suggested — including a hand-added line, which drops out of the list entirely
			// because `suggestions` is where its dates and vendor would have come from and the
			// merge below finds nothing to render. Cancelling that order puts it back on the next
			// read; there is no restore path because there is nothing to restore.
			if (coveredByLiveOrder.contains(ingredientId)) {
				continue;
			}
			Contribution c = e.getValue();
			BigDecimal qty = c.shortfall.max(c.thresholdTopUp).max(c.poOutstanding)
					.setScale(0, RoundingMode.CEILING);

			// T-130. `needed_by` is the date the temple wants the goods on the shelf, and it is the
			// day of the earliest planned meal that demands them — not two days before it. The two
			// days that used to be subtracted here were a delivery buffer standing in for a fact the
			// product did not have; T-090 gave it that fact, per vendor and per ingredient, and it
			// belongs on the other side of the question — the last day the temple can ask, which is
			// LeadTimes.orderBy below. Subtracting both meant the temple asked a vendor to deliver
			// two days before it needed the food, and the order screen then warned that the same date
			// gave the vendor too little notice: applied on the write, complained about on the read.
			LocalDate neededBy = earliestDemand.get(ingredientId);
			// The recorded lead time, or null where nobody has recorded one — kept null rather than
			// defaulted here so a screen can still say the order-by date was our assumption and not
			// the vendor's word. LeadTimes.orderBy applies the fallback; nothing multiplies a null.
			Integer leadTimeDays = recordedLeadTimes.get(ingredientId);
			PreferredVendor vendor = vendors.get(ingredientId);

			out.put(ingredientId, new Suggestion(
					ref,
					qty,
					InventoryUnits.fromBase(
							onHandBase.getOrDefault(ingredientId, BigDecimal.ZERO), ref.unit()),
					neededBy,
					neededBy == null ? null : LeadTimes.orderBy(neededBy, leadTimeDays),
					leadTimeDays,
					vendor == null ? null : vendor.vendorId(),
					vendor == null ? null : vendor.vendorName(),
					c.shortfall,
					c.thresholdTopUp,
					c.poOutstanding,
					c.shortPurchaseOrders));
		}
		return out;
	}

	private static ShoppingListLineView findIn(List<ShoppingListLineView> lines, UUID ingredientId) {
		return lines.stream().filter(l -> l.ingredientId().equals(ingredientId)).findFirst().orElse(null);
	}

	/** The ingredients somebody typed onto the list, which no demand stream will ever reach. */
	private static Set<UUID> handAdded(Map<UUID, Decision> decisions) {
		Set<UUID> ids = new LinkedHashSet<>();
		decisions.forEach((id, d) -> {
			if (d.handAdded()) {
				ids.add(id);
			}
		});
		return ids;
	}

	/** Every human decision this temple has made about its shopping list, keyed by ingredient. */
	private Map<UUID, Decision> decisions() {
		Map<UUID, Decision> map = new LinkedHashMap<>();
		jdbc.query("""
				SELECT ingredient_id, suggested_qty, included, hand_added, updated_at
				FROM shopping_list_lines
				""", rs -> {
			map.put(rs.getObject("ingredient_id", UUID.class), new Decision(
					rs.getBigDecimal("suggested_qty"),
					rs.getBoolean("included"),
					rs.getBoolean("hand_added"),
					rs.getTimestamp("updated_at").toInstant()));
		});
		return map;
	}

	/**
	 * Every ingredient a live purchase order already covers — D-24a, and the one predicate that takes
	 * a line off this list.
	 *
	 * <p><strong>Draft counts, and that is Rajeev's ruling rather than an implementation
	 * detail.</strong> He was asked whether a line should leave on creation or on sending and chose
	 * creation: <em>"IF we take it off on send, they will be there in the shopping list begging to be
	 * ordered, someone else will take pity and generate another PO. Same ingredients, 2 PO's."</em>
	 * Until T-132 nothing here looked at draft orders at all, which is why a freshly generated draft
	 * left its lines sitting on the list looking unordered — the defect he found while writing D-24.
	 *
	 * <p><strong>{@code PARTIALLY_RECEIVED} belongs here too, and that is D-26 reversing what T-132
	 * assumed.</strong> T-132 read a part delivery as evidence of a shortfall — the truck came, and
	 * what it did not bring should come round again — and re-fed the remainder immediately. Rajeev
	 * walked through a real delivery and answered otherwise: 500 kg of rice ordered, the vendor has
	 * 300 and sends it straight away so the kitchen can cook, 200 to follow in two days. <em>"The
	 * 200 KG should still be tied to the PO that raised and sent the 500KG rice order and it should
	 * sit in a partially delivered state and the clock keeps ticking."</em>
	 *
	 * <p>So a part-delivered order is a promise in full exactly as a sent one is: the vendor still
	 * owes the balance, and putting it back on the list would have the temple ordering rice a
	 * supplier is already bringing. The objection to that — a temple short of rice would see nothing
	 * telling them so — is answered by what they do see: a purchase order past due, on the clock,
	 * asking for a decision, which is better than a shopping-list line because it names the vendor
	 * who owes it.
	 *
	 * <p><strong>{@code CLOSED} is the release, and it is an absence rather than an act.</strong>
	 * There is no restore path and no row to write. Closing the order (T-142) moves it out of this
	 * predicate, and the very next read of the list computes the line back — which is the whole of
	 * what "released back to the shopping list" means in a list that is derived. The remainder then
	 * arrives with the PO that fell short named beside it; see
	 * {@link #poOutstandingByIngredient()}.
	 *
	 * <p>Described lines are excluded for the reason given on {@link #poOutstandingByIngredient()}:
	 * {@code ingredient_id} is null on them, and null is a perfectly valid key.
	 */
	private Set<UUID> ingredientsOnLiveOrders() {
		Set<UUID> covered = new LinkedHashSet<>();
		jdbc.query("""
				SELECT DISTINCT pol.ingredient_id
				FROM purchase_order_lines pol
				JOIN purchase_orders po ON po.id = pol.po_id
				WHERE po.status IN ('DRAFT', 'SENT', 'PARTIALLY_RECEIVED')
				  AND pol.ingredient_id IS NOT NULL
				""", rs -> {
			covered.add(rs.getObject("ingredient_id", UUID.class));
		});
		return covered;
	}

	/**
	 * Per ingredient, what a vendor part-delivered and still owes — the ordered quantity minus
	 * everything received so far — summed in base units, with the PO numbers that fell short.
	 * Rejected goods are not received, so they remain outstanding and come round again.
	 *
	 * <p><strong>{@code CLOSED} only, since T-142 — this is the release D-26 describes.</strong> It
	 * was {@code PARTIALLY_RECEIVED} until then, which re-fed the balance the moment a lorry came
	 * short; Rajeev ruled that the balance stays with the vendor while the order is live and comes
	 * back only when the admin closes it. So the two statuses are partitioned between the two
	 * methods, and this one now answers for orders that have ended rather than orders in progress.
	 * ({@code SENT} was counted here before T-132, which put a whole undelivered order back on the
	 * list as though it had never been raised; that is the suppression in
	 * {@link #ingredientsOnLiveOrders()}.)
	 *
	 * <p><strong>And it stops once somebody has acted on it, which needed saying in SQL.</strong> A
	 * closed order is terminal: nothing will ever move it out of CLOSED, so without the NOT EXISTS
	 * below its 200 kg of rice would be re-fed on every read for ever — including after a
	 * replacement order had been raised, delivered in full and received, at which point the list
	 * would ask for 200 kg of rice the temple is standing on. The bound is the same act that takes
	 * any other line off this list: <strong>a purchase order raised for that ingredient since the
	 * close</strong>. Raised, not delivered, because D-24a already made creation the moment a line
	 * is answered — <em>"someone else will take pity and generate another PO. Same ingredients, 2
	 * PO's"</em> — and one rule for both is one thing to learn.
	 *
	 * <p>The edge it leaves open is the safe one: an order raised BEFORE the close and still live
	 * does not count as the answer, so the remainder shows once the live order finishes. That errs
	 * towards putting something in front of a person rather than hiding it, and an untick is the
	 * standing way to say no.
	 *
	 * <p><strong>Described PO lines are excluded, and the exclusion is the whole point</strong>
	 * (T-024). A line may now name something the catalogue has never heard of — four plastic stools
	 * — in which case {@code ingredient_id} is null. This method keys a map by that column, and a
	 * null key is not a refusal: it is a perfectly valid {@code LinkedHashMap} key. Every described
	 * line on every live order would therefore have collapsed into one bucket under the key
	 * {@code null}, adding stools to extension cords in base units, and whatever ingredient row
	 * later asked the map for its outstanding quantity would have got an answer computed from
	 * furniture. Silent, and wrong in the direction that under-orders food.
	 *
	 * <p>Excluded in SQL rather than skipped in the handler so that the intent survives a later edit
	 * to the loop, and so the reason sits next to the column it is about.
	 */
	private Map<UUID, PoOutstanding> poOutstandingByIngredient() {
		Map<UUID, PoOutstanding> map = new LinkedHashMap<>();
		jdbc.query("""
				SELECT pol.ingredient_id, po.po_number, pol.unit,
					   pol.quantity - COALESCE(r.received, 0) AS outstanding
				FROM purchase_order_lines pol
				JOIN purchase_orders po ON po.id = pol.po_id
				LEFT JOIN (
					SELECT po_line_id, SUM(received_qty) AS received
					FROM goods_receipt_lines GROUP BY po_line_id
				) r ON r.po_line_id = pol.id
				WHERE po.status = 'CLOSED'
				  AND pol.ingredient_id IS NOT NULL
				  AND NOT EXISTS (
					SELECT 1
					FROM purchase_order_lines later_line
					JOIN purchase_orders later ON later.id = later_line.po_id
					WHERE later_line.ingredient_id = pol.ingredient_id
					  AND later.created_at > po.closed_at)
				""", rs -> {
			BigDecimal outstanding = rs.getBigDecimal("outstanding");
			if (outstanding == null || outstanding.signum() <= 0) {
				return;
			}
			UUID ingredientId = rs.getObject("ingredient_id", UUID.class);
			BigDecimal base = InventoryUnits.toBase(outstanding, Unit.valueOf(rs.getString("unit")));
			PoOutstanding agg = map.computeIfAbsent(ingredientId,
					k -> new PoOutstanding(BigDecimal.ZERO, new ArrayList<>()));
			agg.poNumbers().add(rs.getString("po_number"));
			map.put(ingredientId, new PoOutstanding(agg.base().add(base), agg.poNumbers()));
		});
		return map;
	}

	private Map<UUID, IngredientRef> ingredientRefs() {
		Map<UUID, IngredientRef> refs = new LinkedHashMap<>();
		jdbc.query("SELECT id, name, canonical_unit FROM ingredients", rs -> {
			refs.put(rs.getObject("id", UUID.class), new IngredientRef(
					rs.getString("name"), Unit.valueOf(rs.getString("canonical_unit"))));
		});
		return refs;
	}

	/**
	 * The vendor each ingredient's order would go to, and its name.
	 *
	 * <p>One query for the whole catalogue rather than {@code VendorService.preferredVendorId} per
	 * line, which is what the regeneration did. That was tolerable on a button press; this runs on
	 * every page load, and a list of forty ingredients would have been forty round trips.
	 * {@code vendor_supplies} is unique on {@code (tenant_id, ingredient_id) WHERE preferred} (V24),
	 * so no ingredient can appear twice here.
	 */
	private Map<UUID, PreferredVendor> preferredVendorsByIngredient() {
		Map<UUID, PreferredVendor> map = new LinkedHashMap<>();
		jdbc.query("""
				SELECT vs.ingredient_id, vs.vendor_id, v.name
				FROM vendor_supplies vs
				JOIN vendors v ON v.id = vs.vendor_id
				WHERE vs.preferred
				""", rs -> {
			map.put(rs.getObject("ingredient_id", UUID.class), new PreferredVendor(
					rs.getObject("vendor_id", UUID.class), rs.getString("name")));
		});
		return map;
	}

	/**
	 * What the store room holds, per ingredient, in base units.
	 *
	 * <p><strong>{@code to_on_hand_qty}, never {@code to_base_qty} (V116, T-122).</strong> A
	 * {@code USED_BEYOND_RECORDED_STOCK} row says a meal was cooked with more of something than the
	 * books held. It is a record of a discrepancy rather than a movement of stock, and it counts as
	 * zero here as it does everywhere else.
	 *
	 * <p>It is worth being explicit about what that costs this list, because it is a real change and
	 * it is the right one. While the shortfall subtracted, an ingredient forty kilos in the red
	 * pulled forty extra kilos onto the shopping list, and that pressure was defended as what gets
	 * the missing delivery written down. It is not: the temple would have bought forty kilos of rice
	 * it may well already have, on the strength of a paperwork failure. The suggestion is now
	 * computed from a shelf of zero, and the thing that gets chased is the row in the ledger with
	 * the ingredient's name in it.
	 *
	 * <p><strong>It aggregates the whole of {@code stock_movements} with no date bound</strong>, on a
	 * table that only ever grows, and since T-132 it does so on every page load rather than on a
	 * button press. T-139 measured it: 71 ms at five years of history, 137 ms at ten, and the cost
	 * tracks the row count almost exactly. <strong>No index fixes that</strong> — the query has no
	 * predicate but the tenant and it wants every row — so the thing to control is how often it runs,
	 * which is what T-140 did. It runs once per page load. Its result is handed to
	 * {@code SufficiencyService.shortfallFeed} and {@code InventoryItemService.lowStock}, both of which
	 * used to ask the same question of the database again.
	 *
	 * <p><strong>The map it returns is the caller's, and it is handed out.</strong> Nothing here or
	 * downstream may draw it down in place: the allocation walk in {@code SufficiencyService} does
	 * exactly that to its own working copy, and takes one deliberately for that reason.
	 */
	private Map<UUID, BigDecimal> onHandBaseByIngredient() {
		Map<UUID, BigDecimal> map = new LinkedHashMap<>();
		jdbc.query("""
				SELECT ingredient_id,
					   SUM(to_on_hand_qty(quantity, unit, movement_type)) AS base
				FROM stock_movements GROUP BY ingredient_id
				""", rs -> {
			map.put(rs.getObject("ingredient_id", UUID.class), rs.getBigDecimal("base"));
		});
		return map;
	}

	private Map<UUID, LocalDate> earliestDemandByIngredient() {
		Map<UUID, LocalDate> map = new LinkedHashMap<>();
		jdbc.query("""
				SELECT ri.ingredient_id, MIN(mp.plan_date) AS earliest
				FROM meal_plans mp
				JOIN recipe_ingredients ri ON ri.recipe_id = mp.recipe_id
				WHERE mp.status = 'PLANNED' AND mp.plan_date >= CURRENT_DATE
				GROUP BY ri.ingredient_id
				""", rs -> {
			map.put(rs.getObject("ingredient_id", UUID.class), rs.getObject("earliest", LocalDate.class));
		});
		return map;
	}

	private static final class Contribution {
		BigDecimal shortfall = BigDecimal.ZERO;
		BigDecimal thresholdTopUp = BigDecimal.ZERO;
		BigDecimal poOutstanding = BigDecimal.ZERO;
		List<String> shortPurchaseOrders = List.of();
	}

	/** One computed line, before anybody has had an opinion about it. */
	private record Suggestion(
			IngredientRef ref,
			BigDecimal quantity,
			BigDecimal currentStock,
			LocalDate neededBy,
			LocalDate orderBy,
			Integer leadTimeDays,
			UUID vendorId,
			String vendorName,
			BigDecimal shortfall,
			BigDecimal thresholdTopUp,
			BigDecimal poOutstanding,
			List<String> shortPurchaseOrders) {
	}

	private record IngredientRef(String name, Unit unit) {
	}

	/** The vendor an ingredient's order would go to, and the name to print beside the line. */
	private record PreferredVendor(UUID vendorId, String vendorName) {
	}

	/** Outstanding PO demand for one ingredient: total in base units and the PO numbers behind it. */
	private record PoOutstanding(BigDecimal base, List<String> poNumbers) {
	}

	/**
	 * One temple's decision about one ingredient. A null {@code quantity} means the person said
	 * nothing about how much — they unticked the line, or typed the figure that was already there.
	 */
	private record Decision(BigDecimal quantity, boolean included, boolean handAdded, Instant updatedAt) {

		/** The temple's own day the decision last changed, for "not ordering — since 12 September". */
		LocalDate decidedOn(ZoneId zone) {
			return updatedAt.atZone(zone).toLocalDate();
		}
	}
}
