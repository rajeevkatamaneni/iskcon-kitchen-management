package org.iskcon.kms.vendor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
 * <p><strong>Drafts and cancelled orders are out.</strong> A draft was never sent to the vendor and
 * a cancellation was the temple's own decision; neither is evidence about a supplier.
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
 * <h2>The three judgements this report makes</h2>
 *
 * <p><strong>A part-delivery stops the on-time clock; it does not stop the fill-rate one.</strong>
 * On-time is measured at the <em>first</em> arrival against the order — did the lorry turn up on the
 * day. That is knowingly generous: a vendor who drops one sack on the due date and the rest a
 * fortnight later scores on-time. It is generous on purpose, because the fill rate beside it is what
 * catches him, and the pair says something neither figure says alone. Measuring on-time at
 * completion instead would collapse the two into one number — short becomes late, and a punctual
 * but chronically short supplier stops being visible as such. It would also be measured on a clock
 * the vendor does not fully control: {@code PurchaseOrderService.isFullyAccountedFor} ignores
 * rejected quantity, so an order with anything refused stays {@code PARTIALLY_RECEIVED} until
 * somebody re-delivers, and "completed" is then partly the temple's own timetable.
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
 * <p><strong>A return does not touch on-time, and that is a decision rather than an
 * oversight.</strong> The two figures answer different questions and the paragraph above is the
 * reason: on-time asks whether the lorry came on the day, fill asks how much of the order the
 * temple actually kept. The goods in a return <em>did</em> arrive on the day; they were sent back
 * later. Letting a return reverse an on-time score would collapse the pair into one number in
 * exactly the way measuring on-time at completion would, and it would do it on a clock the vendor
 * does not control — a return can be booked six weeks after the delivery, so a score already read
 * off the screen would silently change. The one case that is genuinely arguable is a
 * {@code NOT_DELIVERED} return, where the receipt itself was a keying error and nothing ever
 * arrived; that is a judgement at order grain rather than line grain, Rajeev has not been asked to
 * rule on it, and it is left alone here rather than smuggled in beside a ruling about fill.
 *
 * <p><strong>"Arrival" is two things, and it has to be (T-066).</strong> A goods receipt is one. The
 * other is a described line recorded as having arrived — four plastic stools, a mixer motor repaired
 * — which can never take a receipt at all, because the store room does not track it and
 * {@code goods_receipt_lines.ingredient_id} is NOT NULL. Before T-066 there was no way to record
 * that such an order had been delivered, so an order of nothing but described lines had no first
 * receipt, could not have one, and scored <em>late for ever</em>: a permanent black mark against a
 * supplier for our schema's shape rather than for anything they did. On-time now reads the earlier
 * of the two. The alternative Rajeev considered and rejected — excluding such orders from on-time
 * judgement entirely — was quieter and would have made a vendor who genuinely never delivered the
 * stools indistinguishable from one who delivered them on the day.
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
 * <p>{@code needed_by} lives on the purchase-order header, not on the line. So on-time is measured
 * <strong>per order, not per ingredient</strong>: an order of eight things is one on-time
 * observation, whichever of the eight was late. That is the right grain for a scorecard and the
 * model is deliberately not being changed to improve it — a per-line date would have to be captured
 * by whoever raises the order, on every line, for a report; and a screen labelled "orders on time"
 * is honest about what it counted.
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
		countOrders(byVendor, from, to, today);
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
				everything.ordersWithoutNeededBy, percent(everything.onTimeOrders, everything.ordersJudged),
				everything.linesJudged, everything.fillRate(), everything.rejectedLines,
				everything.openOrders, everything.openCurrent, everything.openDue1To30,
				everything.openOverdue31Plus, List.copyOf(rows));
	}

	// ---------------------------------------------------------------------

	/**
	 * Every order placed in the period, and whether anything arrived against it in time.
	 *
	 * <p>The first arrival, not the last: see the class comment. The comparison is made in the
	 * temple's own day, because {@code received_at} is an instant and {@code needed_by} is a date,
	 * and a delivery booked late on the needed-by evening is not the following morning's failure.
	 *
	 * <p><strong>Two subqueries because an order can arrive in two ways</strong> (T-066). The
	 * receipts table answers for everything the store room takes in. {@code arrived_on} answers for
	 * the lines it cannot — a described line is acknowledged on the order rather than received into
	 * stock — and it is already a temple-zone date, which is why only the first of the two is passed
	 * through {@code templeDate}. Whichever came first is when this order turned up.
	 *
	 * <p>An order with neither still scores late, and that is the point of doing it this way rather
	 * than by excluding described orders from judgement: nobody has said the stools arrived, so as
	 * far as this report knows they did not.
	 */
	private void countOrders(Map<UUID, Totals> byVendor, LocalDate from, LocalDate to, LocalDate today) {
		jdbc.query("""
				SELECT po.vendor_id, po.needed_by,
					   (SELECT MIN(gr.received_at) FROM goods_receipts gr WHERE gr.po_id = po.id)
						   AS first_receipt_at,
					   (SELECT MIN(pol.arrived_on) FROM purchase_order_lines pol
						 WHERE pol.po_id = po.id AND pol.arrived_on IS NOT NULL)
						   AS first_arrival_on
				FROM purchase_orders po
				WHERE
				""" + LIVE_ORDER + """
				  AND po.order_date BETWEEN ? AND ?
				""", rs -> {
			Totals totals = totalsFor(byVendor, rs.getObject("vendor_id", UUID.class));
			totals.ordersPlaced++;
			LocalDate neededBy = rs.getObject("needed_by", LocalDate.class);
			if (neededBy == null) {
				totals.ordersWithoutNeededBy++;
				return;
			}
			if (!neededBy.isBefore(today)) {
				return; // Still has time. Counted in the open columns and nowhere else.
			}
			totals.ordersJudged++;
			LocalDate firstArrival = earlier(
					templeDate(rs.getObject("first_receipt_at", OffsetDateTime.class)),
					rs.getObject("first_arrival_on", LocalDate.class));
			if (firstArrival != null && !firstArrival.isAfter(neededBy)) {
				totals.onTimeOrders++;
			}
		}, from, to);
	}

	/**
	 * The earlier of two days, either of which may be absent. Null only when both are: an order that
	 * took a goods receipt and never an acknowledgement, or the other way round, arrived on the one
	 * date there is.
	 */
	private static LocalDate earlier(LocalDate a, LocalDate b) {
		if (a == null) {
			return b;
		}
		if (b == null) {
			return a;
		}
		return a.isBefore(b) ? a : b;
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

	private LocalDate templeDate(OffsetDateTime at) {
		return at == null ? null : at.atZoneSameInstant(clock.zone()).toLocalDate();
	}

	/** A whole percentage. The counts behind it are on the screen beside it, so tenths add nothing. */
	private static BigDecimal percent(int numerator, int denominator) {
		if (denominator <= 0) {
			return null;
		}
		return BigDecimal.valueOf(numerator).multiply(HUNDRED)
				.divide(BigDecimal.valueOf(denominator), 0, RoundingMode.HALF_UP);
	}

	private record VendorRef(UUID id, String name, boolean active) {
	}

	private static final class Totals {
		private int ordersPlaced;
		private int ordersJudged;
		private int onTimeOrders;
		private int ordersWithoutNeededBy;
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
			ordersWithoutNeededBy += other.ordersWithoutNeededBy;
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

		private VendorPerformanceRow asRow(VendorRef ref) {
			List<RejectionCount> byReason = new ArrayList<>();
			rejections.forEach((reason, lines) -> byReason.add(new RejectionCount(reason, lines)));
			byReason.sort(Comparator.comparingInt(RejectionCount::lines).reversed()
					.thenComparing(RejectionCount::reason));
			return new VendorPerformanceRow(ref.id(), ref.name(), ref.active(),
					ordersPlaced, ordersJudged, onTimeOrders, ordersWithoutNeededBy,
					percent(onTimeOrders, ordersJudged), linesJudged, fillRate(),
					rejectedLines, List.copyOf(byReason),
					openOrders, openCurrent, openDue1To30, openOverdue31Plus,
					ordersJudged >= MIN_ORDERS_TO_RANK);
		}
	}
}
