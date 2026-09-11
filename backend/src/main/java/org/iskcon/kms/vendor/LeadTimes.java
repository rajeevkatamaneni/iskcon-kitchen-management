package org.iskcon.kms.vendor;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How long the temple has to wait between asking for something and it arriving, and the date that
 * follows from it (T-090).
 *
 * <p>Rajeev's rule, from his review of 2026-09-08: <em>"order-by date = the date it is needed minus
 * the lead time."</em> This is the one place that arithmetic is done, so the planner badge and the
 * shopping list cannot come to disagree about the last day something could have been ordered.
 *
 * <h2>Where the number comes from, and the one rule for finding it</h2>
 *
 * <p>Lead time is recorded per <em>(vendor, ingredient)</em> — {@code vendor_supplies.lead_time_days},
 * V119 — because it is a fact about this supplier and this thing rather than about either alone. The
 * rice merchant may deliver rice next morning and take a week over jaggery he has to fetch.
 *
 * <p>The figure used for an ingredient is <strong>the one recorded against the vendor the order will
 * actually go to</strong>: the preferred vendor, which is what {@code ShoppingListService} suggests
 * and what {@code VendorService.preferredVendorId} answers. Taking the longest lead time across
 * everyone who supplies it was considered and rejected — it plans against a supplier nobody is going
 * to ring, and it makes the order-by date move when an unrelated vendor is added to a catalogue.
 * One rule, and it is the rule the person doing the ordering would use.
 *
 * <h2>Unknown is not zero, and it is not an error either</h2>
 *
 * <p>A supply row with no recorded lead time means nobody has said. It emphatically does not mean
 * the goods arrive the same day; scoring it that way would tell a cook there was time when there was
 * none, which is the failure this whole feature exists to prevent. Nor is it a refusal — a temple
 * with no lead times recorded anywhere must still get a usable shopping list on its first day.
 *
 * <p>So an unrecorded lead time falls back to {@link #ASSUMED_LEAD_TIME_DAYS}, and {@link
 * #recordedByIngredient()} returns null for it so that a screen can still say the date was our
 * assumption rather than the vendor's word.
 *
 * <h2>Two days appears twice in this application and means two different things</h2>
 *
 * <p>Worth stating plainly, because the constants look alike and the confusion is expensive:
 *
 * <ul>
 *   <li><strong>Lead time</strong> (here) — how long the vendor takes once asked. It answers
 *       <em>"when is the last day we can ask?"</em>, and the answer is the order-by date.</li>
 *   <li><strong>The delivery buffer</strong> ({@code ShoppingListService.LEAD_BUFFER_DAYS}) — how
 *       much earlier than the meal the temple wants the goods on the shelf. It answers <em>"what
 *       date do we write on the purchase order?"</em>, and the answer is {@code needed_by}, which
 *       {@code PurchaseOrderService} copies onto the order and sends to the supplier.</li>
 * </ul>
 *
 * <p>Both happen to be two days today, which is exactly why they were easy to conflate. A recorded
 * lead time supersedes the assumption <em>here</em> and leaves the delivery buffer alone — changing
 * that would change what every generated order asks a supplier for, which is a decision about the
 * product rather than a side effect of adding a field.
 */
@Service
public class LeadTimes {

	/**
	 * What a vendor is assumed to need where nobody has recorded an answer.
	 *
	 * <p>Two days, because two days is what this product has assumed since E5-S2 and the frontend
	 * has said out loud on the order screen since — <em>"Sooner than the 2 days a vendor usually
	 * gets"</em>. Inventing a second, different guess so that the first one could be superseded
	 * would leave two numbers arguing where there is only one question.
	 */
	public static final int ASSUMED_LEAD_TIME_DAYS = 2;

	private final JdbcTemplate jdbc;

	public LeadTimes(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * The lead time governing each of these purchase orders: the longest recorded against the
	 * order's own vendor for the ingredients actually on it (T-137, D-25).
	 *
	 * <p><strong>The longest governs, and it is a decision rather than an inference.</strong> Lead
	 * time is per vendor <em>and</em> ingredient, so one order can carry several — a dairy may bring
	 * curd next morning and take three days over ghee it has to fetch. Rajeev, asked directly on
	 * 2026-09-10, chose the longest: the order is only fully deliverable when its slowest item is,
	 * and an order-by date computed off the quickest line would promise a delivery the vendor never
	 * agreed to.
	 *
	 * <p><strong>The order's own vendor, not the preferred one.</strong> {@link
	 * #recordedByIngredient()} above answers a question about a shopping list, where nobody has
	 * chosen a supplier yet and the preferred vendor is the best guess at who will be rung. An order
	 * has no guessing left in it: it is addressed to somebody, and the promise that binds is theirs.
	 *
	 * <p>An order is <strong>absent from this map</strong> when no line on it has a recorded lead
	 * time against its vendor — a vendor nobody has asked, an ingredient they do not have a supply
	 * row for, or an order of nothing but described lines ("four plastic stools"), which name no
	 * catalogue ingredient and so can carry no recorded lead time at all. All of those mean the same
	 * thing and get the same treatment: no cutoff, and silence. {@code MAX} ignores nulls, so a
	 * vendor who has answered for the rice and not for the jaggery governs the order by the rice —
	 * which is the most we honestly know they agreed to.
	 *
	 * <p>One query for the whole set rather than one per order, because every caller has a list: the
	 * order screen has one order, the purchase-order list and the Today dashboard have every draft.
	 *
	 * <p><strong>Never call this for an order that has already been sent.</strong> What such an
	 * order went out under is stamped on it ({@code purchase_orders.lead_time_days}), and reading it
	 * live would let an edit to a vendor's profile re-judge deliveries that already happened — the
	 * precise thing Rajeev ruled out: <em>"Any SLA Adjustments made to a vendor's profile will take
	 * effect for the Orders after the change. No retroactive change here."</em>
	 */
	@Transactional(readOnly = true)
	public Map<UUID, Integer> governingByPurchaseOrder(Collection<UUID> purchaseOrderIds) {
		Map<UUID, Integer> map = new LinkedHashMap<>();
		if (purchaseOrderIds.isEmpty()) {
			return map;
		}
		String placeholders = String.join(", ", java.util.Collections.nCopies(purchaseOrderIds.size(), "?"));
		jdbc.query("""
				SELECT po.id AS po_id, MAX(vs.lead_time_days) AS lead_time_days
				FROM purchase_orders po
				JOIN purchase_order_lines pol ON pol.po_id = po.id
				JOIN vendor_supplies vs
					ON vs.vendor_id = po.vendor_id AND vs.ingredient_id = pol.ingredient_id
				WHERE po.id IN (%s)
				GROUP BY po.id
				HAVING MAX(vs.lead_time_days) IS NOT NULL
				""".formatted(placeholders), rs -> {
			map.put(rs.getObject("po_id", UUID.class), rs.getInt("lead_time_days"));
		}, purchaseOrderIds.toArray());
		return map;
	}

	/**
	 * The lead time recorded against each ingredient's preferred vendor, for every ingredient that
	 * has one.
	 *
	 * <p><strong>An ingredient is absent from this map for two different reasons and the caller must
	 * treat them the same:</strong> no preferred vendor is designated, or the preferred vendor's
	 * supply row has no lead time on it. Both mean "nobody has said", both fall back, and neither is
	 * worth distinguishing to somebody reading a screen — what they would do about it is identical.
	 *
	 * <p>Read as one query rather than per ingredient because both callers want the whole catalogue:
	 * the badge walks a fortnight of meals and the shopping list every line it is about to write.
	 * {@code vendor_supplies} is unique on {@code (tenant_id, ingredient_id) WHERE preferred} (V24),
	 * so no ingredient can appear twice here.
	 */
	@Transactional(readOnly = true)
	public Map<UUID, Integer> recordedByIngredient() {
		Map<UUID, Integer> map = new LinkedHashMap<>();
		jdbc.query("""
				SELECT ingredient_id, lead_time_days
				FROM vendor_supplies
				WHERE preferred AND lead_time_days IS NOT NULL
				""", rs -> {
			map.put(rs.getObject("ingredient_id", UUID.class), rs.getInt("lead_time_days"));
		});
		return map;
	}

	/**
	 * The days to plan with: what was recorded, or the assumption where nothing was.
	 *
	 * <p>Takes the boxed {@code Integer} rather than an {@code int} on purpose — the null is the
	 * whole point of the method, and unboxing it at the call site is the mistake this exists to stop.
	 */
	public static int effectiveDays(Integer recorded) {
		return recorded == null ? ASSUMED_LEAD_TIME_DAYS : recorded;
	}

	/**
	 * The last day something needed on {@code neededOn} can be ordered and still arrive: Rajeev's
	 * rule, and the only place it is written.
	 *
	 * @param neededOn the day the food is cooked, or the earliest such day where several want it
	 * @param recorded the lead time recorded against the preferred vendor, or null where none was
	 */
	public static LocalDate orderBy(LocalDate neededOn, Integer recorded) {
		return neededOn.minusDays(effectiveDays(recorded));
	}
}
