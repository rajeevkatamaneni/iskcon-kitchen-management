package org.iskcon.kms.vendor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * How the temple's suppliers have performed (E5-S9).
 *
 * <p>Asked entirely of data the temple already records: the needed-by date on a purchase order, the
 * receipts booked against it, the quantities on those receipt lines and the reasons anything was
 * refused. Nothing new is captured to produce this, and nothing here is an estimate.
 *
 * <p><strong>On-time is scored per item</strong> (T-124, from Rajeev's five delivery scenarios of
 * 2026-09-09). Each item on an order contributes the fraction of it that was there on or before the
 * needed-by day, capped at one; an order is the mean of its items and a vendor the mean of their
 * orders. A cancellation somebody has marked "Vendor Never Delivered this Order" scores nothing and
 * is counted again in {@code abandonedOrders}; a cancellation nobody has marked is counted nowhere
 * at all.
 *
 * <p><strong>Two clocks, deliberately.</strong> Everything counted over the period is selected by
 * the date the order was <em>placed</em> — one rule, so a reader never has to ask which date put a
 * row where it is. The open-order and aging columns are present tense and unfiltered: an order is
 * open now or it is not, and hiding a June order from an August report would hide the very thing
 * aging is for.
 *
 * @param vendors worst on-time first, so reading the column downwards is the answer; suppliers with
 *                too few judged orders to rank sit below that, by name.
 */
public record VendorPerformance(
		LocalDate from,
		LocalDate to,
		int ordersPlaced,
		int ordersJudged,
		int onTimeOrders,
		int abandonedOrders,
		int ordersWithoutNeededBy,
		int itemsScored,
		int itemsOnTime,
		BigDecimal onTimePercent,
		int linesJudged,
		BigDecimal fillRatePercent,
		int rejectedLines,
		int openOrders,
		int openCurrent,
		int openDue1To30,
		int openOverdue31Plus,
		List<VendorPerformanceRow> vendors) {
}
