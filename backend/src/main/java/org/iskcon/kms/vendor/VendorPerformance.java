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
 * <p><strong>An order we sent after the vendor's agreed lead time is counted as placed and then
 * left out of the on-time figure</strong> (T-137, D-25): we asked for something their notice period
 * could not deliver, so a delay on it is not theirs. {@code ordersSentLate} says how many, beside
 * the percentage, because exclusions that cannot be seen cannot be checked.
 *
 * <p><strong>And an order closed part-delivered with its shortfall excused leaves that vendor's
 * figures entirely</strong> (T-142, D-26): both the on-time percentage and the fill rate, because
 * the black mark on a part-delivery is mostly the half-empty lorry. {@code ordersExcused} says how
 * many, beside the percentages. The admin's influence over a supplier's score is that name and
 * nothing else — Rajeev, having proposed a dial, ruled it out: <em>"Let us not let the admin adjust
 * the score. Just show it to them."</em>
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
		int ordersSentLate,
		int ordersExcused,
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
