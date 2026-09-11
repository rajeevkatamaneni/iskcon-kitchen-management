package org.iskcon.kms.vendor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.receiving.ReturnReason;
import org.iskcon.kms.tenancy.TempleClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplier scorecarding — requested against actual delivery, what is still open and how old it is,
 * what was refused and how much of an order actually turns up (E5-S9).
 *
 * <p><strong>No new data is captured for this.</strong> Every figure is a question asked of records
 * the temple already keeps: {@code purchase_orders.needed_by} is what was asked for,
 * {@code goods_receipts.received_at} is what happened, {@code goods_receipt_lines} holds the
 * quantities and the reasons, and both receipt tables are append-only, so the history behind a
 * number cannot have been edited after the fact. The rejection reasons were kept for exactly this
 * ({@code RejectReason}), and E5-S6's last acceptance criterion promised it.
 *
 * <h2>What counts, and what cannot</h2>
 *
 * <p><strong>Drafts are out, and so is a cancellation nobody has blamed the vendor for.</strong> A
 * draft was never sent. A cancellation is usually the temple's own decision — the festival moved,
 * the kitchen found a cheaper source — and none of that is evidence about a supplier. The one
 * exception is the fifth of Rajeev's delivery scenarios (T-124, 2026-09-09): <em>"nothing ever
 * came; we cancelled and went elsewhere"</em>. That cancellation is the only record the temple has
 * of a supplier's worst possible performance, and while every cancellation was excluded the vendor
 * who never turned up was invisible on this report while the one who was merely late was not. So
 * {@code purchase_orders.vendor_abandoned} — the tick box on the cancel dialog — brings exactly
 * those back: scored zero, counted as abandoned in their own column, and named.
 *
 * <p><strong>An unticked cancellation is counted nowhere at all</strong>, neither on-time nor
 * abandoned, and that is the safe direction rather than an omission. Silence blames nobody, which
 * is what a box left alone should mean.
 *
 * <p><strong>And an order we sent after the vendor's own lead time is left out of the on-time
 * figure entirely</strong> (T-137, D-25). Rajeev put the principle first: <em>"We can't forget the
 * Golden Rule: Hold others to the same standards you want to be held to."</em> A lead time is the
 * supplier's own number, agreed at onboarding and padded on purpose, and if we submit an order
 * after the last day it could have been filled then <em>"that is a FAVOR we are asking"</em> — so a
 * delay on that delivery cannot be counted towards their performance. The order was placed and is
 * counted as placed; it is simply not judged, and {@code ordersSentLate} says how many were set
 * aside that way.
 *
 * <p><strong>And an order closed part-delivered with its shortfall excused is left out of both
 * figures</strong> (T-142, D-26). This is the second of the two endings Rajeev walked through: the
 * supplier who rings, apologises, blames the weather, offers a discount next time and says buy it
 * elsewhere. The admin closing the order can waive the black mark — and the waiver takes the order
 * out of the fill rate as well as the on-time figure, because on a part-delivery the black mark is
 * mostly the half-empty lorry and excusing only the lateness would waive almost nothing. The other
 * ending, <em>the vendor let us down</em>, needs no arithmetic at all: the missing quantity is
 * already in the percentage, and the name is there so a reader can tell a 60% the temple blames
 * from one it accepts.
 *
 * <p><strong>What is NOT here, and must never be.</strong> There is no adjustment, no dial and no
 * column an admin can move a number with. Rajeev proposed one — <em>"there is SO MUCH human
 * interaction that no machine or app can capture"</em> — and then ruled against his own proposal:
 * <em>"Let us not let the admin adjust the score. Just show it to them."</em> An adjustable score
 * stops being a measurement; nobody ever adjusts downward; "an admin changed it" is no answer to a
 * vendor who disputes their score; and it would undo in one control everything D-25 and T-129
 * closed the same day to make this number defensible.
 *
 * <p><strong>The count is on the screen beside the percentage, and that is part of the ruling
 * rather than a nicety.</strong> The figures now exclude orders we submitted late, and he was
 * explicit that this must be visible rather than quietly changing a number: a reader who cannot see
 * what was excluded cannot check the number. It is the same standard the abandoned count already
 * meets.
 *
 * <p><strong>The verdict is read off the order, never recomputed.</strong>
 * {@code purchase_orders.sent_after_lead_time} was decided once, in Java, at the moment of sending
 * (V125). This report does no date arithmetic about lead times at all, which is what makes an edit
 * to a vendor's profile unable to re-judge an order already placed — <em>"No retroactive change
 * here."</em>
 *
 * <p><strong>The fill rate does not move, and that is decided rather than overlooked.</strong>
 * Ordering late excuses a supplier for being <em>late</em>; it does not excuse them for never
 * bringing half the rice. And T-124's own argument applies with full force — an exclusion that
 * silently changed an existing percentage on an existing screen is the defect that ruling exists to
 * avoid. If Rajeev wants a late-placed order left out of the fill rate too, that is one predicate
 * and it should be his decision rather than this task's inference.
 *
 * <p><strong>The period selects orders by the date they were placed.</strong> One rule for
 * everything counted over a period, so no reader has to work out which date put a row where. The
 * open-order and aging columns are the exception and say so: they are present tense, unfiltered by
 * period, because an order left hanging since June is precisely what aging exists to surface.
 *
 * <p><strong>An order is judged once its needed-by date has passed.</strong> Strictly passed — an
 * order due today can still arrive today. Until then the vendor still has time and the order is
 * counted nowhere but the open columns. An order with no needed-by date can never be judged: there
 * is nothing to be late against, so it is counted aside rather than scored a silent hundred per
 * cent.
 *
 * <p><strong>An abandoned cancellation is the exception, and it is judged the moment it is
 * cancelled</strong> (T-124), whether or not its needed-by date has passed. Ticking that box is
 * itself the claim that the vendor is not coming; waiting for a date to arrive before believing it
 * would be waiting for information nobody is going to send.
 *
 * <h2>The three judgements this report makes</h2>
 *
 * <p><strong>On-time is scored per item, and it is the average of what turned up in time</strong>
 * (T-124, from Rajeev's five delivery scenarios of 2026-09-09). Each order line contributes the
 * fraction of its ordered quantity that arrived on a receipt dated on or before the needed-by day,
 * clamped to one — over-delivery is not a bonus. An order's score is the mean of its lines' and a
 * vendor's is the mean of their orders'. So eight of ten items in the window and two a week late is
 * 80%, and an order split across two days that are both inside the window is 100%: what matters is
 * whether the goods were there in time, not how many lorries brought them.
 *
 * <p><strong>What it replaced, and why the old rule looked right for so long.</strong> On-time used
 * to be binary per order and read the <em>earliest</em> arrival — {@code MIN(first_receipt_at)}
 * against {@code needed_by} — so one sack on day one made a whole order punctual however little
 * else ever came. That was a deliberate, argued position: on-time asked whether the lorry turned up
 * on the day and the fill rate beside it was what caught a short delivery, and the pair was said to
 * say something neither figure says alone. The flaw is that the two figures are not
 * interchangeable. Fill measures how much of the order eventually arrived, over any timescale at
 * all; it is silent about <em>when</em>. So a supplier who brought eight of ten items on the day
 * and the last two a fortnight late scored a hundred per cent on-time and a hundred per cent fill,
 * and nothing anywhere on the screen said the kitchen had cooked without them. Rajeev's third
 * scenario is exactly that order, and he scored it 80%.
 *
 * <p><strong>Rejected quantity still counts as having arrived, and that half of the old rule is
 * kept on purpose.</strong> On-time asks whether the goods were at the gate on the day; a sack
 * refused there was. What became of it afterwards is the fill rate's question and the rejection
 * column's, and folding it in here would collapse two figures into one in exactly the way scoring
 * on-time at completion would. Measuring at completion would also run on a clock the vendor does
 * not fully control: {@code PurchaseOrderService.isFullyAccountedFor} ignores rejected quantity, so
 * an order with anything refused stays {@code PARTIALLY_RECEIVED} until somebody re-delivers, and
 * "completed" is then partly the temple's own timetable.
 *
 * <p><strong>The fill rate does not move</strong> (T-124, decided while building rather than
 * assumed). An abandoned order entering the fill rate would drag it to zero and quietly change what
 * an existing number on an existing screen means — the same defect as summing a table after a new
 * state has been added to it. On-time and the abandoned count answer Rajeev's question between
 * them; fill does not need to move to help, and moving it would falsify every fill rate anybody has
 * already read.
 *
 * <p><strong>One property to accept knowingly:</strong> averaging order scores means a one-line
 * order weighs the same as a fifty-line one. The alternative — weighing by size — lets a single
 * large order swamp a year of small ones. Neither is wrong; this is the one on the screen, and the
 * item counts printed beside the percentage are what let a reader see the difference.
 *
 * <p><strong>Goods sent back afterwards come off the fill rate, if the vendor is why they went
 * back (T-103, ruled by Rajeev on 2026-09-10).</strong> Until this ruling the fill rate summed
 * {@code goods_receipt_lines.received_qty} and nothing else, and a return does not touch that
 * column — it writes its own negative {@code RETURN_TO_VENDOR} movement — so a vendor whose fifty
 * kilos of rice all went back for weevils scored exactly as one whose rice was fine. The reason was
 * on every return already ({@link ReturnReason}); it was simply never read. Three positions were
 * put to Rajeev — leave the figure as a measure of what arrived at the gate, subtract every return,
 * or subtract only the vendor's own failures — and he took the third, on the argument that a temple
 * over-ordering and sending stock back is not the supplier's failure while weevils and a wrong item
 * are. {@link #VENDOR_FAULT_RETURNS} is that ruling, and {@code OTHER} is deliberately outside it.
 *
 * <p><strong>Most returns do not touch on-time; a {@code NOT_DELIVERED} one does, because nothing
 * ever came</strong> (T-124). The two figures answer different questions and the paragraph above is
 * the reason: on-time asks whether the goods were there in time, fill asks how much of the order
 * the temple kept. The goods in an ordinary return <em>did</em> arrive on the day and were sent
 * back later, so letting weevils reverse an on-time score would collapse the pair into one number
 * and would do it on a clock the vendor does not control — a return can be booked six weeks after
 * the delivery, and a score already read off the screen would silently change.
 *
 * <p>{@code NOT_DELIVERED} is not that. It is the fifty-keyed-for-five case: the receipt line
 * itself was a keying error and the goods it claims never existed. Taking that quantity back out of
 * the on-time count is not a new rule at all — it is the same sum with a wrong number removed, and
 * it is what closes the hole T-103's builder found and correctly declined to widen its own ruling
 * over (T-109). Because on-time is now scored per item, it needs no order-grain rule for the
 * partial case: three lines keyed correctly and one keyed in error score three and a fraction of a
 * fourth, which is what actually happened.
 *
 * <p><strong>"Arrival" is two things, and it has to be (T-066).</strong> A goods receipt is one. The
 * other is a described line recorded as having arrived — four plastic stools, a mixer motor repaired
 * — which can never take a receipt at all, because the store room does not track it and
 * {@code goods_receipt_lines.ingredient_id} is NOT NULL. Before T-066 there was no way to record
 * that such an order had been delivered, so an order of nothing but described lines had no first
 * receipt, could not have one, and scored <em>late for ever</em>: a permanent black mark against a
 * supplier for our schema's shape rather than for anything they did. The alternative Rajeev
 * considered and rejected — excluding such orders from on-time judgement entirely — was quieter and
 * would have made a vendor who genuinely never delivered the stools indistinguishable from one who
 * delivered them on the day.
 *
 * <p><strong>So a described line scores yes or no, off {@code arrived_on}</strong> (T-124). There
 * is no quantity to weigh: nobody records that two of the four stools turned up, and
 * {@code arrived_on} is a single date for the whole line by construction
 * ({@code po_lines_arrival_is_recorded_whole}). Weighing it as if it were a quantity would invent a
 * precision the record does not have. It is a full item on the order either way, so it counts once
 * in the mean alongside the rice.
 *
 * <p><strong>A vendor with few orders is shown, not ranked.</strong> No statistical model, no
 * confidence interval, no hiding of the figure. Below {@link #MIN_ORDERS_TO_RANK} judged orders the
 * row is marked and sorted beneath the ranked ones, and the counts behind every percentage are on
 * the screen beside it. With three orders, one late lorry moves the figure thirty points, which
 * makes it a statement about the sample rather than about the supplier.
 *
 * <p><strong>A deactivated vendor stays on the report, marked.</strong> Their history is the exact
 * thing somebody consults before bringing them back, and the reason they were dropped is often in
 * these very numbers. Leaving them off would delete the evidence for the decision.
 *
 * <h2>The one modelling limit</h2>
 *
 * <p>{@code needed_by} lives on the purchase-order header, not on the line. Every item on an order
 * is therefore judged against the same day, and an order whose rice was genuinely wanted on Monday
 * and whose stools would have done any time that month is scored as though both were wanted on
 * Monday. The model is deliberately not being changed to improve that: a per-line date would have
 * to be typed by whoever raises the order, on every line, for a report — and the temple asks for
 * one date because it wants one delivery.
 *
 * <p>What T-124 changed is the grain of the <em>answer</em>, not of the question. One needed-by
 * date, ten items measured against it.
 */
@Service
public class VendorPerformanceService {


	/** The longest period the report will cover, matching the message {@code KMS-400122} already carries. */
	private static final int MAX_PERIOD_DAYS = 366;

	/**
	 * Below this many judged orders a supplier is shown but not ranked.
	 *
	 * <p>A handful. Not a threshold with a theory behind it — there is no model here and inventing
	 * one would be worse than the honest counts already on the screen — but the point at which one
	 * late delivery stops moving the percentage by more than twenty points.
	 */
	static final int MIN_ORDERS_TO_RANK = 5;

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	/** Orders that were actually placed with a vendor: everything but a draft and a cancellation. */
	private static final String LIVE_ORDER = "po.status NOT IN ('DRAFT', 'CANCELLED')";

	/**
	 * The orders on-time is scored over: every live one, plus a cancellation somebody has said the
	 * vendor caused (T-124).
	 *
	 * <p><strong>Deliberately not the predicate the fill rate and the rejection counts use.</strong>
	 * They keep {@link #LIVE_ORDER}, because an abandoned order entering the fill rate would drag it
	 * to zero and silently change what an existing figure on an existing screen means. Two
	 * predicates, one difference, and the difference is the ruling.
	 */
	private static final String SCORED_ORDER = """
			(po.status NOT IN ('DRAFT', 'CANCELLED')
			 OR (po.status = 'CANCELLED' AND po.vendor_abandoned))
			""";

	/**
	 * The return reasons the fill rate holds against the supplier (T-103, ruled 2026-09-10).
	 *
	 * <p>Four of {@link ReturnReason}'s five. {@code OTHER} is the omission and it is the whole
	 * ruling: the person returning the goods explains themselves in a free-text note, and the
	 * commonest thing that note says is that the temple ordered more than it could use. That is not
	 * a supplier's failure and the scorecard must not read it as one.
	 *
	 * <p><strong>Named one by one rather than written as "everything except OTHER".</strong> The two
	 * spellings behave identically today and differently on the day somebody adds a sixth reason: the
	 * exclusion would enrol it against every vendor silently, this set leaves it out until somebody
	 * rules on it. A new reason belongs in front of Rajeev, not in a percentage that has already been
	 * read off a screen.
	 */
	private static final Set<ReturnReason> VENDOR_FAULT_RETURNS = EnumSet.of(
			ReturnReason.DAMAGED, ReturnReason.SPOILED, ReturnReason.WRONG_ITEM,
			ReturnReason.NOT_DELIVERED);

	/**
	 * {@link #VENDOR_FAULT_RETURNS} as a SQL list. Interpolated rather than bound, which is safe for
	 * the one reason interpolation is ever safe: every element is a Java enum constant's name, so it
	 * cannot contain a quote and cannot come from a request. Binding it would mean an argument count
	 * that changes with the enum, in a query whose other three arguments are positional.
	 */
	private static final String VENDOR_FAULT_REASONS = VENDOR_FAULT_RETURNS.stream()
			.map(reason -> "'" + reason.name() + "'")
			.collect(Collectors.joining(", "));

	/**
	 * How much of one purchase-order line's delivered quantity went back to the vendor as the
	 * vendor's own failure. A scalar subquery per line, joined through the receipt line because that
	 * is what a return points at — {@code goods_returns} has no order and no vendor of its own, and
	 * is not meant to: the receipt line it reverses knows both.
	 */
	private static final String SENT_BACK_TO_VENDOR = """
			COALESCE((SELECT SUM(ret.quantity)
					  FROM goods_returns ret
					  JOIN goods_receipt_lines rl ON rl.id = ret.receipt_line_id
					  WHERE rl.po_line_id = pol.id
						AND ret.reason IN (%s)), 0) AS sent_back
			""".formatted(VENDOR_FAULT_REASONS);

	private final TempleClock clock;
	private final JdbcTemplate jdbc;

	public VendorPerformanceService(JdbcTemplate jdbc, TempleClock clock) {
		this.clock = clock;
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public VendorPerformance report(LocalDate from, LocalDate to) {
		if (to.isBefore(from) || from.plusDays(MAX_PERIOD_DAYS).isBefore(to)) {
			throw new ApplicationException(ErrorCode.COST_PERIOD_NOT_VALID, Map.of("from", from, "to", to));
		}
		LocalDate today = LocalDate.now(clock.zone());

		Map<UUID, Totals> byVendor = new LinkedHashMap<>();
		// The items first, then the orders that own them: an order's score is the mean of its
		// lines', so the line arithmetic has to be complete before any order can be marked.
		countOrders(byVendor, scoreItems(from, to, today), from, to, today);
		countLines(byVendor, from, to, today);
		countRejections(byVendor, from, to);
		countOpenOrders(byVendor, today);

		Map<UUID, VendorRef> vendors = vendorRefs();
		List<VendorPerformanceRow> rows = new ArrayList<>();
		Totals everything = new Totals();
		for (Map.Entry<UUID, Totals> entry : byVendor.entrySet()) {
			VendorRef ref = vendors.get(entry.getKey());
			if (ref == null) {
				continue;
			}
			rows.add(entry.getValue().asRow(ref));
			everything.add(entry.getValue());
		}

		// Worst on-time first: the report exists to find the supplier who is letting the kitchen
		// down, and reading the column downwards should be the answer. A vendor with too few judged
		// orders has no place in that ordering and sits below it, by name — present, plainly not
		// ranked — and one with nothing judged at all has no percentage to sort on.
		rows.sort(Comparator
				.comparing((VendorPerformanceRow row) -> !row.enoughToRank())
				.thenComparing(VendorPerformanceRow::onTimePercent,
						Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(VendorPerformanceRow::vendorName, String.CASE_INSENSITIVE_ORDER));

		return new VendorPerformance(from, to,
				everything.ordersPlaced, everything.ordersJudged, everything.onTimeOrders,
				everything.abandonedOrders, everything.ordersWithoutNeededBy,
				everything.ordersSentLate, everything.ordersExcused,
				everything.itemsScored, everything.itemsOnTime, everything.onTimePercent(),
				everything.linesJudged, everything.fillRate(), everything.rejectedLines,
				everything.openOrders, everything.openCurrent, everything.openDue1To30,
				everything.openOverdue31Plus, List.copyOf(rows));
	}

	// ---------------------------------------------------------------------

	/**
	 * How much of each item on a judged order was there in time, as a fraction of what was ordered
	 * (T-124).
	 *
	 * <p>Keyed by purchase order, because an order's score is the mean of its items' and this is the
	 * only place the items are visible. An order with no lines at all produces no entry, and
	 * {@link #countOrders} then leaves it unjudged rather than scoring it: there is nothing to
	 * average, and a zero would be a statement about a supplier made out of an empty order.
	 *
	 * <p><strong>The two ways an item can be there, and they are exclusive by schema</strong>
	 * (T-024, T-066). A catalogue line is answered by the goods receipts booked against it and by
	 * nothing else. A described line — four plastic stools — can never take a receipt at all and is
	 * answered by {@code arrived_on}, yes or no. {@code ingredient_id IS NULL} is the discriminator
	 * and the database enforces the exclusivity, so no line can be counted twice.
	 *
	 * <p><strong>The date comparison is made in SQL, in the temple's own zone.</strong>
	 * {@code received_at} is an instant and {@code needed_by} is a date, and a delivery booked late
	 * on the needed-by evening in Bengaluru is not the following morning's failure. It is done here
	 * rather than in Java because what is wanted is a conditional <em>sum</em> — how much arrived in
	 * time, not when the first thing did — and pulling every receipt line back to add it up in Java
	 * would be the same arithmetic further from the data. The zone is bound as a parameter and cast
	 * explicitly, because {@code AT TIME ZONE ?} alone leaves PostgreSQL unable to infer the
	 * parameter's type.
	 *
	 * <p><strong>Rejected quantity counts as having arrived</strong> — see the class comment. It was
	 * at the gate on the day; what happened to it next is the fill rate's question and the rejection
	 * column's.
	 *
	 * <p><strong>A {@code NOT_DELIVERED} return comes back off, and only the part of it that was
	 * booked in time.</strong> That return says the receipt line was a keying error and the goods
	 * never existed, so the quantity has to leave the on-time count the same way it leaves the fill
	 * rate. The date filter on the returns subquery mirrors the one above it for the reason the
	 * whole method exists: a correction to a late receipt must not reduce what arrived punctually.
	 *
	 * <p><strong>An abandoned order's items are read but not scored.</strong> They are here so that
	 * the item counts beside the percentage still add up — ten items ordered, none of them on time —
	 * and {@link #countOrders} forces the order's score to zero regardless of what the columns say.
	 * A described line on such an order could in principle carry an {@code arrived_on}; the tick box
	 * is the later and more deliberate statement, and it wins.
	 */
	private Map<UUID, OrderScore> scoreItems(LocalDate from, LocalDate to, LocalDate today) {
		return scoreItemsWhere(SCORED_ORDER + """
				  AND po.order_date BETWEEN ? AND ?
				  -- We asked for the impossible, so nothing here is theirs to answer for (D-25).
				  -- The order is still counted as placed; see countOrders.
				  AND NOT po.sent_after_lead_time
				  -- And they fell short but made it right, so the temple has said this one is not
				  -- to be held against them (T-142, D-26). Counted as placed and set aside in its
				  -- own column, exactly as the line above is.
				  AND po.close_outcome IS DISTINCT FROM 'SHORTFALL_EXCUSED'
				  AND (po.vendor_abandoned
					   OR (po.needed_by IS NOT NULL AND po.needed_by < ?))
				""", from, to, today);
	}

	/**
	 * What one order scored, for the screen that closes it (T-142, D-26).
	 *
	 * <p><strong>The same arithmetic as the report, narrowed to one order — never a second copy of
	 * it.</strong> The person closing a part-delivered order is shown what the vendor actually
	 * scored on it, and if that figure were worked out separately they would be shown one number
	 * while the scorecard printed another. Both would then be defended by somebody, and the whole
	 * reason D-26 shows the figure at all — so that a judgement is made against a fact rather than a
	 * feeling — would be gone.
	 *
	 * <p><strong>It answers for the order as it stands, with none of the report's exclusions.</strong>
	 * No period, no needed-by-has-passed test, no lead-time exclusion, no excusing. Those decide
	 * whether an order belongs in a <em>vendor's</em> figures; this answers what <em>this</em> order
	 * scored, which is the question in front of the person about to decide what it means. Where the
	 * report will then set the order aside — we sent it late, or it is closed excused — the screen
	 * says so beside the number rather than hiding it.
	 *
	 * <p>Null percent where there is nothing to score: no needed-by date to be late against, or no
	 * lines at all.
	 */
	@Transactional(readOnly = true)
	public OrderDeliveryScore scoreOfOrder(UUID purchaseOrderId) {
		OrderScore score = scoreItemsWhere("""
				pol.po_id = ?
				  AND po.needed_by IS NOT NULL
				""", purchaseOrderId).get(purchaseOrderId);
		return score == null
				? OrderDeliveryScore.nothingToScore()
				: new OrderDeliveryScore(
						score.mean().multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP),
						score.items, score.fullyOnTime);
	}

	/**
	 * The item arithmetic, once, with the caller saying which orders it is asked about.
	 *
	 * <p>Extracted when T-142 needed one order's score for the closing screen. The SELECT is the
	 * part that must never be written twice — how much of a line arrived in time, how a described
	 * line answers, what a NOT_DELIVERED return takes back out — and the WHERE is the part that
	 * legitimately differs between the report and one order.
	 *
	 * @param where the rest of the WHERE clause; its bind parameters follow the two zone parameters
	 *              the SELECT itself needs, which is why {@code args} is spread after them
	 */
	private Map<UUID, OrderScore> scoreItemsWhere(String where, Object... args) {
		Map<UUID, OrderScore> byOrder = new LinkedHashMap<>();
		String zone = clock.zone().getId();
		Object[] all = new Object[args.length + 2];
		all[0] = zone;
		all[1] = zone;
		System.arraycopy(args, 0, all, 2, args.length);
		jdbc.query("""
				SELECT po.id AS po_id, po.needed_by, po.vendor_abandoned,
					   pol.ingredient_id, pol.quantity, pol.arrived_on,
					   COALESCE((SELECT SUM(grl.received_qty + grl.rejected_qty)
								 FROM goods_receipt_lines grl
								 JOIN goods_receipts gr ON gr.id = grl.receipt_id
								 WHERE grl.po_line_id = pol.id
								   AND (gr.received_at AT TIME ZONE CAST(? AS text))::date
									   <= po.needed_by), 0) AS arrived_in_time,
					   -- And what a NOT_DELIVERED return says was never there to begin with (T-124).
					   COALESCE((SELECT SUM(ret.quantity)
								 FROM goods_returns ret
								 JOIN goods_receipt_lines rl ON rl.id = ret.receipt_line_id
								 JOIN goods_receipts gr ON gr.id = rl.receipt_id
								 WHERE rl.po_line_id = pol.id
								   AND ret.reason = 'NOT_DELIVERED'
								   AND (gr.received_at AT TIME ZONE CAST(? AS text))::date
									   <= po.needed_by), 0) AS never_came
				FROM purchase_order_lines pol
				JOIN purchase_orders po ON po.id = pol.po_id
				WHERE
				""" + where, rs -> {
			OrderScore score = byOrder.computeIfAbsent(rs.getObject("po_id", UUID.class),
					k -> new OrderScore());
			score.add(itemFraction(rs.getBoolean("vendor_abandoned"),
					rs.getObject("ingredient_id", UUID.class) == null,
					rs.getObject("needed_by", LocalDate.class),
					rs.getObject("arrived_on", LocalDate.class),
					rs.getBigDecimal("quantity"),
					rs.getBigDecimal("arrived_in_time"),
					rs.getBigDecimal("never_came")));
		}, all);
		return byOrder;
	}

	/**
	 * One item's share of itself that was there in time, somewhere in {@code [0, 1]}.
	 *
	 * <p>Clamped at both ends and for different reasons. The ceiling is the ruling: bringing twelve
	 * sacks where ten were ordered is not a bonus that pays for a late line elsewhere on the order.
	 * The floor is defensive — {@code GoodsReturnService} caps cumulative returns at the receipt
	 * line's {@code received_qty} ({@code KMS-400140}), so arithmetic alone should never get there,
	 * but a negative fraction would drag down this vendor's <em>other</em> orders through the mean
	 * and produce a figure nobody could reconcile against the counts printed beside it.
	 */
	private static BigDecimal itemFraction(boolean abandoned, boolean described, LocalDate neededBy,
			LocalDate arrivedOn, BigDecimal ordered, BigDecimal arrivedInTime, BigDecimal neverCame) {
		if (abandoned) {
			return BigDecimal.ZERO;
		}
		if (described) {
			// Yes or no, off the acknowledgement: there is no quantity to weigh, and inventing one
			// would be a precision the record does not have. See the class comment.
			return arrivedOn != null && !arrivedOn.isAfter(neededBy) ? BigDecimal.ONE : BigDecimal.ZERO;
		}
		if (ordered == null || ordered.signum() <= 0) {
			return BigDecimal.ZERO;
		}
		return arrivedInTime.subtract(neverCame).max(BigDecimal.ZERO)
				.divide(ordered, 6, RoundingMode.HALF_UP)
				.min(BigDecimal.ONE);
	}

	/**
	 * Every order placed in the period, and how well it was delivered.
	 *
	 * <p>Three outcomes and a fourth that is not an outcome at all. An abandoned cancellation is
	 * judged straight away and scores nothing — ticking the box is itself the claim that the vendor
	 * is not coming, so there is no date left to wait for. An order with no needed-by date has
	 * nothing to be late against and is counted aside rather than scored a silent hundred per cent.
	 * An order still inside its date is counted in the open columns and nowhere else. Everything
	 * else is judged on the mean of its items.
	 *
	 * <p>The fourth case is an order with no lines on it, which cannot happen through the
	 * application — the order screen refuses to remove the last line — and would have no items to
	 * average if it did. It is counted as placed and left unjudged, which is the same treatment the
	 * report already gives an order there is nothing to say about yet.
	 */
	private void countOrders(Map<UUID, Totals> byVendor, Map<UUID, OrderScore> scores,
			LocalDate from, LocalDate to, LocalDate today) {
		jdbc.query("""
				SELECT po.id AS po_id, po.vendor_id, po.needed_by, po.vendor_abandoned,
					   po.sent_after_lead_time, po.close_outcome
				FROM purchase_orders po
				WHERE
				""" + SCORED_ORDER + """
				  AND po.order_date BETWEEN ? AND ?
				""", rs -> {
			Totals totals = totalsFor(byVendor, rs.getObject("vendor_id", UUID.class));
			totals.ordersPlaced++;
			OrderScore score = scores.get(rs.getObject("po_id", UUID.class));

			// Checked before anything else, the abandoned tick included (D-25). We submitted this
			// order after the vendor's agreed notice period, so there is no delivery of theirs to
			// judge — not a late one, and not a missing one either. Counted as placed and set aside
			// in its own column so a reader can see what the percentage leaves out.
			if (rs.getBoolean("sent_after_lead_time")) {
				totals.ordersSentLate++;
				return;
			}
			// Then the temple's own decision at closing (T-142, D-26). The vendor fell short and
			// made it right — rang up, apologised, offered a discount next time and said buy it
			// elsewhere — so the order leaves their figures with the reason recorded on it.
			//
			// After the lead-time test and not before, so an order that is BOTH sent late and
			// closed excused is counted once, in the column that says the lateness was ours. The
			// two counts are exclusive by construction and ordersPlaced reconciles against them.
			if ("SHORTFALL_EXCUSED".equals(rs.getString("close_outcome"))) {
				totals.ordersExcused++;
				return;
			}
			if (rs.getBoolean("vendor_abandoned")) {
				totals.ordersJudged++;
				totals.abandonedOrders++;
				// Zero, and the items counted so the note beside the percentage still adds up.
				totals.itemsScored += score == null ? 0 : score.items;
				return;
			}
			LocalDate neededBy = rs.getObject("needed_by", LocalDate.class);
			if (neededBy == null) {
				totals.ordersWithoutNeededBy++;
				return;
			}
			if (!neededBy.isBefore(today)) {
				return; // Still has time. Counted in the open columns and nowhere else.
			}
			if (score == null) {
				return; // Nothing on the order to score. See this method's comment.
			}
			totals.ordersJudged++;
			BigDecimal mean = score.mean();
			totals.onTimeScore = totals.onTimeScore.add(mean);
			if (mean.compareTo(BigDecimal.ONE) == 0) {
				totals.onTimeOrders++;
			}
			totals.itemsScored += score.items;
			totals.itemsOnTime += score.fullyOnTime;
		}, from, to);
	}

	/**
	 * The items on one purchase order and what fraction of each was there in time.
	 *
	 * <p>{@code fullyOnTime} counts only the items that scored a whole one. It is what the screen
	 * says beside the percentage — eight of ten — and it is deliberately not the same information as
	 * the percentage: an order of two items, one of them half delivered on the day, is 75% with one
	 * item fully on time, and a reader should be able to see both.
	 */
	private static final class OrderScore {

		private int items;
		private int fullyOnTime;
		private BigDecimal total = BigDecimal.ZERO;

		private void add(BigDecimal fraction) {
			items++;
			total = total.add(fraction);
			if (fraction.compareTo(BigDecimal.ONE) == 0) {
				fullyOnTime++;
			}
		}

		private BigDecimal mean() {
			return total.divide(BigDecimal.valueOf(items), 6, RoundingMode.HALF_UP);
		}
	}

	/**
	 * How much of each judged order's lines actually turned up and was kept.
	 *
	 * <p>Per line as a fraction, never as a sum of quantities: 36 kilos of rice and 10 litres of oil
	 * do not add up to 46 of anything, and a vendor's fill rate must not depend on which units their
	 * ingredients happen to be held in. Accepted quantity only — a rejected sack was delivered but it
	 * did not feed anybody, and it is counted again by reason in its own column.
	 *
	 * <p><strong>A described line is not judged at all</strong> (T-024), and the clause that leaves
	 * it out is a ruling rather than a filter, so please do not remove it as dead weight. A line that
	 * names something the catalogue has never heard of — four plastic stools, two extension cords —
	 * is orderable and payable but can never be <em>received</em>: {@code ReceivingService} refuses a
	 * receipt line against one ({@code KMS-400129}) and the NOT NULL on
	 * {@code goods_receipt_lines.ingredient_id} would refuse it after that. Its accepted quantity is
	 * therefore not merely unknown; it is permanently and structurally zero. Counted here it would be
	 * a zero-fill entry that never clears — buy four stools from a wholesaler and their delivery
	 * performance falls for ever, on a report that exists to be a judgement about a supplier. That
	 * would make it a judgement about our own schema instead. A fill rate is the fraction of what was
	 * asked for that turned up, and this report can only honestly ask that of the lines the store
	 * room is able to take in.
	 *
	 * <p>The boundary is deliberate too: an order of nothing but described lines contributes no
	 * judged lines, so a vendor with no other business in the period shows a <em>blank</em> fill rate
	 * beside a lines-judged count of zero, not 0%. That is the choice the class comment already makes
	 * for an order with no needed-by date — counted aside rather than scored a figure it did not earn
	 * — and {@code Totals.fillRate()} returns null on a zero denominator, so the cell is empty next to
	 * the count that explains it. The vendor still appears on the report through their orders and
	 * their open columns; only the fill-rate cell is silent, which is the truthful thing for it to be.
	 *
	 * <p><strong>Kept, not merely accepted (T-103).</strong> What is measured is the quantity the
	 * temple both took in and still has a use for: the receipt lines' {@code received_qty} less
	 * everything sent back to that vendor for a reason in {@link #VENDOR_FAULT_RETURNS}. The two
	 * figures are read separately and subtracted here rather than netted in SQL, so that a reader of
	 * this method can see which of them the ruling touched.
	 *
	 * <p>Three things about the subtraction that were decided rather than fallen into:
	 *
	 * <p><strong>It is a quantity, not a disqualification.</strong> Returns are cumulative and
	 * usually partial — forty-five kilos of a fifty-kilo delivery, not the lot — so forty-five off
	 * fifty ordered leaves a tenth filled, not a zero. Treating a returned line as unfilled would
	 * make the common case wrong in order to make the rare case tidy.
	 *
	 * <p><strong>It cannot drive the line below zero.</strong> {@code GoodsReturnService} already
	 * caps cumulative returns at the receipt line's {@code received_qty} ({@code KMS-400140}), so
	 * arithmetic alone should never get there; the clamp is here because a report is the wrong place
	 * to discover otherwise. A negative fraction would drag down a vendor's <em>other</em> deliveries
	 * through the average, which is a figure nobody could reconcile against the counts beside it. The
	 * floor and the existing ceiling are a pair, and a line now contributes somewhere in
	 * {@code [0, 1]} whatever the data says.
	 *
	 * <p><strong>A return is counted against the order it came in on, whenever it was made.</strong>
	 * The period picks orders by {@code order_date}, as everything else in this report does, and the
	 * return is then read without a date filter of its own. So weevils found in November against an
	 * October delivery move October's figure. That is the same shape as the rejection counts below
	 * and it is what makes the figure stable to read: the alternative — only returns booked inside
	 * the window — would let the same October order score differently depending on the day somebody
	 * ran the report.
	 */
	private void countLines(Map<UUID, Totals> byVendor, LocalDate from, LocalDate to, LocalDate today) {
		jdbc.query("""
				SELECT po.vendor_id, pol.quantity,
					   COALESCE((SELECT SUM(grl.received_qty) FROM goods_receipt_lines grl
								 WHERE grl.po_line_id = pol.id), 0) AS accepted,
					   -- And what went back afterwards because the vendor got it wrong (T-103).
					   """ + SENT_BACK_TO_VENDOR + """
				FROM purchase_order_lines pol
				JOIN purchase_orders po ON po.id = pol.po_id
				WHERE
				""" + LIVE_ORDER + """
				  AND po.order_date BETWEEN ? AND ?
				  AND po.needed_by IS NOT NULL AND po.needed_by < ?
				  -- Closed, with the shortfall excused, so it leaves the fill rate as well as the
				  -- on-time figure (T-142, D-26). This is NOT the same case as D-25's late-sent
				  -- order, which T-137 deliberately left in the fill rate: ordering late excuses
				  -- OUR timing and not a half-empty lorry, whereas this excuses THEIR shortfall,
				  -- which is precisely the question the fill rate asks. Waiving the black mark and
				  -- leaving 60% standing in the column that measures the shortfall would waive
				  -- almost nothing. The count is on the screen beside both percentages.
				  AND po.close_outcome IS DISTINCT FROM 'SHORTFALL_EXCUSED'
				  -- A described line can never be received, so it can never be filled.
				  -- See this method's comment before removing this (T-024).
				  AND pol.ingredient_id IS NOT NULL
				""", rs -> {
			Totals totals = totalsFor(byVendor, rs.getObject("vendor_id", UUID.class));
			BigDecimal ordered = rs.getBigDecimal("quantity");
			if (ordered == null || ordered.signum() <= 0) {
				return;
			}
			BigDecimal kept = rs.getBigDecimal("accepted").subtract(rs.getBigDecimal("sent_back"));
			BigDecimal filled = kept.max(BigDecimal.ZERO).divide(ordered, 6, RoundingMode.HALF_UP);
			totals.linesJudged++;
			totals.filled = totals.filled.add(filled.min(BigDecimal.ONE));
		}, from, to, today);
	}

	/** Delivery lines refused on the period's orders, by reason. */
	private void countRejections(Map<UUID, Totals> byVendor, LocalDate from, LocalDate to) {
		jdbc.query("""
				SELECT po.vendor_id, grl.reject_reason, count(*) AS lines
				FROM goods_receipt_lines grl
				JOIN goods_receipts gr ON gr.id = grl.receipt_id
				JOIN purchase_orders po ON po.id = gr.po_id
				WHERE
				""" + LIVE_ORDER + """
				  AND grl.rejected_qty > 0
				  AND po.order_date BETWEEN ? AND ?
				GROUP BY po.vendor_id, grl.reject_reason
				""", rs -> {
			Totals totals = totalsFor(byVendor, rs.getObject("vendor_id", UUID.class));
			int lines = rs.getInt("lines");
			totals.rejectedLines += lines;
			totals.rejections.merge(rs.getString("reject_reason"), lines, Integer::sum);
		}, from, to);
	}

	/**
	 * What is still open with each vendor, right now, aged the way the payables screen ages an
	 * unpaid invoice — the same three buckets, the same boundaries, the same names. A second idea of
	 * "overdue" in one application is something a person has to learn rather than read.
	 */
	private void countOpenOrders(Map<UUID, Totals> byVendor, LocalDate today) {
		jdbc.query("""
				SELECT po.vendor_id, po.needed_by
				FROM purchase_orders po
				WHERE po.status IN ('SENT', 'PARTIALLY_RECEIVED')
				""", rs -> {
			Totals totals = totalsFor(byVendor, rs.getObject("vendor_id", UUID.class));
			totals.openOrders++;
			switch (agingBucket(rs.getObject("needed_by", LocalDate.class), today)) {
				case "DUE_1_30" -> totals.openDue1To30++;
				case "OVERDUE_31_PLUS" -> totals.openOverdue31Plus++;
				default -> totals.openCurrent++;
			}
		});
	}

	/**
	 * The payables buckets, unchanged, read against the needed-by date instead of a due date. An
	 * order with no needed-by date is {@code CURRENT} for the same reason an invoice with no due date
	 * is: nothing has been missed if nothing was asked for.
	 */
	private static String agingBucket(LocalDate neededBy, LocalDate today) {
		if (neededBy == null || !neededBy.isBefore(today)) {
			return "CURRENT";
		}
		return java.time.temporal.ChronoUnit.DAYS.between(neededBy, today) <= 30
				? "DUE_1_30" : "OVERDUE_31_PLUS";
	}

	private Map<UUID, VendorRef> vendorRefs() {
		Map<UUID, VendorRef> refs = new LinkedHashMap<>();
		jdbc.query("SELECT id, name, active FROM vendors", rs -> {
			refs.put(rs.getObject("id", UUID.class),
					new VendorRef(rs.getObject("id", UUID.class), rs.getString("name"), rs.getBoolean("active")));
		});
		return refs;
	}

	private static Totals totalsFor(Map<UUID, Totals> byVendor, UUID vendorId) {
		return byVendor.computeIfAbsent(vendorId, k -> new Totals());
	}

	private record VendorRef(UUID id, String name, boolean active) {
	}

	private static final class Totals {
		private int ordersPlaced;
		private int ordersJudged;
		private int onTimeOrders;
		private int abandonedOrders;
		private int ordersWithoutNeededBy;
		/** Orders we submitted after the vendor's agreed lead time, and so do not judge (D-25). */
		private int ordersSentLate;
		/** Orders closed with their shortfall excused, and so judged nowhere (T-142, D-26). */
		private int ordersExcused;
		private int itemsScored;
		private int itemsOnTime;
		/**
		 * The sum of the judged orders' scores, each somewhere in {@code [0, 1]}. Divided by
		 * {@code ordersJudged} it is the on-time percentage; kept as a running sum rather than an
		 * average so that the totals row can add two vendors together without averaging averages.
		 */
		private BigDecimal onTimeScore = BigDecimal.ZERO;
		private int linesJudged;
		private BigDecimal filled = BigDecimal.ZERO;
		private int rejectedLines;
		private final Map<String, Integer> rejections = new LinkedHashMap<>();
		private int openOrders;
		private int openCurrent;
		private int openDue1To30;
		private int openOverdue31Plus;

		private void add(Totals other) {
			ordersPlaced += other.ordersPlaced;
			ordersJudged += other.ordersJudged;
			onTimeOrders += other.onTimeOrders;
			abandonedOrders += other.abandonedOrders;
			ordersWithoutNeededBy += other.ordersWithoutNeededBy;
			ordersSentLate += other.ordersSentLate;
			ordersExcused += other.ordersExcused;
			itemsScored += other.itemsScored;
			itemsOnTime += other.itemsOnTime;
			onTimeScore = onTimeScore.add(other.onTimeScore);
			linesJudged += other.linesJudged;
			filled = filled.add(other.filled);
			rejectedLines += other.rejectedLines;
			openOrders += other.openOrders;
			openCurrent += other.openCurrent;
			openDue1To30 += other.openDue1To30;
			openOverdue31Plus += other.openOverdue31Plus;
		}

		private BigDecimal fillRate() {
			return linesJudged <= 0 ? null
					: filled.multiply(HUNDRED)
							.divide(BigDecimal.valueOf(linesJudged), 0, RoundingMode.HALF_UP);
		}

		/**
		 * The mean of the judged orders' scores, as a whole percentage. Null where nothing has been
		 * judged: a figure divided by nothing is worse than no figure.
		 */
		private BigDecimal onTimePercent() {
			return ordersJudged <= 0 ? null
					: onTimeScore.multiply(HUNDRED)
							.divide(BigDecimal.valueOf(ordersJudged), 0, RoundingMode.HALF_UP);
		}

		private VendorPerformanceRow asRow(VendorRef ref) {
			List<RejectionCount> byReason = new ArrayList<>();
			rejections.forEach((reason, lines) -> byReason.add(new RejectionCount(reason, lines)));
			byReason.sort(Comparator.comparingInt(RejectionCount::lines).reversed()
					.thenComparing(RejectionCount::reason));
			return new VendorPerformanceRow(ref.id(), ref.name(), ref.active(),
					ordersPlaced, ordersJudged, onTimeOrders, abandonedOrders, ordersWithoutNeededBy,
					ordersSentLate, ordersExcused,
					itemsScored, itemsOnTime, onTimePercent(), linesJudged, fillRate(),
					rejectedLines, List.copyOf(byReason),
					openOrders, openCurrent, openDue1To30, openOverdue31Plus,
					ordersJudged >= MIN_ORDERS_TO_RANK);
		}
	}
}
