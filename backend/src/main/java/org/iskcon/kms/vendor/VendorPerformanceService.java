package org.iskcon.kms.vendor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
 * On-time is measured at the <em>first</em> receipt against the order — did the lorry turn up on the
 * day. That is knowingly generous: a vendor who drops one sack on the due date and the rest a
 * fortnight later scores on-time. It is generous on purpose, because the fill rate beside it is what
 * catches him, and the pair says something neither figure says alone. Measuring on-time at
 * completion instead would collapse the two into one number — short becomes late, and a punctual
 * but chronically short supplier stops being visible as such. It would also be measured on a clock
 * the vendor does not fully control: {@code ReceivingService.isFullyReceived} ignores rejected
 * quantity, so an order with anything refused stays {@code PARTIALLY_RECEIVED} until somebody
 * re-delivers, and "completed" is then partly the temple's own timetable.
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

	/**
	 * The same zone every other date-sensitive service reads. A delivery booked at 00:30 IST is the
	 * temple's morning, not the previous day, and the browser's idea of today is its own timezone's.
	 */
	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	/** The longest period the report will cover, matching the message {@code KMS-4988} already carries. */
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

	private final JdbcTemplate jdbc;

	public VendorPerformanceService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public VendorPerformance report(LocalDate from, LocalDate to) {
		if (to.isBefore(from) || from.plusDays(MAX_PERIOD_DAYS).isBefore(to)) {
			throw new ApplicationException(ErrorCode.COST_PERIOD_NOT_VALID, Map.of("from", from, "to", to));
		}
		LocalDate today = LocalDate.now(TEMPLE_ZONE);

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
	 * <p>The first receipt, not the last: see the class comment. The comparison is made in the
	 * temple's own day, because {@code received_at} is an instant and {@code needed_by} is a date,
	 * and a delivery booked late on the needed-by evening is not the following morning's failure.
	 */
	private void countOrders(Map<UUID, Totals> byVendor, LocalDate from, LocalDate to, LocalDate today) {
		jdbc.query("""
				SELECT po.vendor_id, po.needed_by,
					   (SELECT MIN(gr.received_at) FROM goods_receipts gr WHERE gr.po_id = po.id)
						   AS first_receipt_at
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
			LocalDate firstReceipt = templeDate(rs.getObject("first_receipt_at", OffsetDateTime.class));
			if (firstReceipt != null && !firstReceipt.isAfter(neededBy)) {
				totals.onTimeOrders++;
			}
		}, from, to);
	}

	/**
	 * How much of each judged order's lines actually turned up and was kept.
	 *
	 * <p>Per line as a fraction, never as a sum of quantities: 36 kilos of rice and 10 litres of oil
	 * do not add up to 46 of anything, and a vendor's fill rate must not depend on which units their
	 * ingredients happen to be held in. Accepted quantity only — a rejected sack was delivered but it
	 * did not feed anybody, and it is counted again by reason in its own column.
	 */
	private void countLines(Map<UUID, Totals> byVendor, LocalDate from, LocalDate to, LocalDate today) {
		jdbc.query("""
				SELECT po.vendor_id, pol.quantity,
					   COALESCE((SELECT SUM(grl.received_qty) FROM goods_receipt_lines grl
								 WHERE grl.po_line_id = pol.id), 0) AS accepted
				FROM purchase_order_lines pol
				JOIN purchase_orders po ON po.id = pol.po_id
				WHERE
				""" + LIVE_ORDER + """
				  AND po.order_date BETWEEN ? AND ?
				  AND po.needed_by IS NOT NULL AND po.needed_by < ?
				""", rs -> {
			Totals totals = totalsFor(byVendor, rs.getObject("vendor_id", UUID.class));
			BigDecimal ordered = rs.getBigDecimal("quantity");
			if (ordered == null || ordered.signum() <= 0) {
				return;
			}
			BigDecimal accepted = rs.getBigDecimal("accepted");
			BigDecimal filled = accepted.divide(ordered, 6, RoundingMode.HALF_UP);
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

	private static LocalDate templeDate(OffsetDateTime at) {
		return at == null ? null : at.atZoneSameInstant(TEMPLE_ZONE).toLocalDate();
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
