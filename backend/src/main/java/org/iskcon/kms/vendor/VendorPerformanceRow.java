package org.iskcon.kms.vendor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One supplier's record over the reported period (E5-S9).
 *
 * <p>Every percentage here travels with the counts it was made from, and the screen shows both. A
 * percentage on its own is a lie a scorecard tells easily: "50% on time" is a different statement
 * about a vendor with two orders and one with forty, and only the denominator says which.
 *
 * @param ordersPlaced        orders placed with this vendor in the period, drafts and cancelled
 *                            orders excluded. A draft was never sent to them and a cancelled order
 *                            was the temple's decision; neither can be held against a supplier.
 * @param ordersJudged        of those, the ones whose needed-by date has passed — the vendor's
 *                            chance is over and the order can be marked. The denominator of both
 *                            percentages below.
 * @param onTimeOrders        of the judged orders, those where something actually arrived on or
 *                            before the needed-by date. Measured at the <em>first</em> delivery, so
 *                            it says the lorry turned up — never that it brought everything, which
 *                            is what {@code fillRatePercent} is for.
 * @param ordersWithoutNeededBy orders in the period with no needed-by date at all. There is nothing
 *                            to be late against, so they are outside both figures and counted here
 *                            instead of quietly scoring the vendor a hundred per cent.
 * @param onTimePercent       null where nothing has been judged yet — a figure divided by nothing is
 *                            worse than no figure.
 * @param linesJudged         order lines on the judged orders; the denominator of the fill rate.
 * @param fillRatePercent     the share of an average ordered line the vendor actually delivered and
 *                            the temple accepted. Over-delivery is capped at 100% per line: bringing
 *                            twice the coriander does not make up for bringing no rice.
 * @param rejectedLines       delivery lines refused on this vendor's period orders, any reason.
 * @param rejections          the same count split by reason, commonest first.
 * @param openOrders          orders still open with this vendor <em>right now</em>, whenever they
 *                            were placed. Deliberately not filtered to the period: an order left
 *                            hanging since June is exactly what the aging columns exist to surface,
 *                            and a period filter would hide it.
 * @param enoughToRank        false below {@code VendorPerformanceService.MIN_ORDERS_TO_RANK} judged
 *                            orders. The figures are still shown — hiding them is its own lie — but
 *                            the row sits below the ranked ones and is marked, because with three
 *                            orders one late lorry moves the percentage by thirty points and the
 *                            number is then about the sample rather than the supplier.
 */
public record VendorPerformanceRow(
		UUID vendorId,
		String vendorName,
		boolean active,
		int ordersPlaced,
		int ordersJudged,
		int onTimeOrders,
		int ordersWithoutNeededBy,
		BigDecimal onTimePercent,
		int linesJudged,
		BigDecimal fillRatePercent,
		int rejectedLines,
		List<RejectionCount> rejections,
		int openOrders,
		int openCurrent,
		int openDue1To30,
		int openOverdue31Plus,
		boolean enoughToRank) {
}
