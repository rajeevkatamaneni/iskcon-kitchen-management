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
 * @param ordersPlaced        orders placed with this vendor in the period. Drafts are excluded — one
 *                            was never sent to them — and so is a cancellation nobody has blamed on
 *                            the vendor, which was the temple's own decision. A cancellation marked
 *                            "Vendor Never Delivered this Order" <em>is</em> included (T-124): it is
 *                            the only record the temple has of a supplier who never came.
 * @param ordersJudged        of those, the ones there is now something to say about: their needed-by
 *                            date has passed, or they were abandoned, which is judged the moment the
 *                            box is ticked. The denominator of the on-time percentage.
 * @param onTimeOrders        of the judged orders, those that scored a full hundred per cent — every
 *                            item on them there in time. Not the numerator of {@code onTimePercent},
 *                            deliberately: an order eight-tenths delivered on the day counts towards
 *                            the percentage and not towards this, and a reader should see both.
 * @param abandonedOrders     of the judged orders, those cancelled because the vendor never
 *                            delivered them (T-124). Each scores nothing, and this is the count that
 *                            says a zero came from a supplier who never turned up rather than from
 *                            one who turned up late.
 * @param ordersWithoutNeededBy orders in the period with no needed-by date at all. There is nothing
 *                            to be late against, so they are outside both figures and counted here
 *                            instead of quietly scoring the vendor a hundred per cent.
 * @param ordersSentLate      orders in the period that <em>we</em> submitted after this vendor's
 *                            agreed lead time (T-137, D-25). They are counted as placed and then
 *                            set aside: we asked for something their notice period could not
 *                            deliver, so a delay on one is not theirs to answer for. The count is
 *                            on the screen beside the percentage because a figure whose exclusions
 *                            are invisible cannot be checked — the same standard {@code
 *                            abandonedOrders} already meets. The fill rate still counts them, which
 *                            is deliberate: ordering late excuses lateness, not a half-empty lorry.
 * @param ordersExcused       orders in the period closed part-delivered with the shortfall excused
 *                            — <em>they fell short but made it right</em> (T-142, D-26). The
 *                            supplier rang, apologised, offered a discount next time and said buy
 *                            it elsewhere, and the admin closing the order said so. Counted as
 *                            placed and then set aside from BOTH the on-time figure and the fill
 *                            rate: the black mark on a part-delivery is mostly the half-empty
 *                            lorry, so excusing only the lateness would waive almost nothing. That
 *                            is deliberately unlike {@code ordersSentLate}, which stays in the fill
 *                            rate, and the difference is nameable — ordering late excuses our
 *                            timing, this excuses their shortfall. The count is on the screen
 *                            beside the percentages because an exclusion that cannot be seen
 *                            cannot be checked, and there is no control anywhere that moves the
 *                            number itself: "Let us not let the admin adjust the score. Just show
 *                            it to them."
 * @param itemsScored         order lines that went into the on-time figure — the "of ten" in "eight
 *                            of ten items". A described line ("four plastic stools") counts as one
 *                            item like any other; the fill rate cannot judge it, on-time can.
 * @param itemsOnTime         of those, the ones fully there in time. Eight of ten is a different
 *                            statement from a bare 80%, and this is what makes the difference
 *                            visible: two items missing entirely, or ten items all a fifth short.
 * @param onTimePercent       the mean of the judged orders' scores. Null where nothing has been
 *                            judged yet — a figure divided by nothing is worse than no figure.
 * @param linesJudged         order lines on the judged orders; the denominator of the fill rate.
 *                            Not the same population as {@code itemsScored}: fill leaves out
 *                            described lines and abandoned orders, on-time counts both.
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
		List<RejectionCount> rejections,
		int openOrders,
		int openCurrent,
		int openDue1To30,
		int openOverdue31Plus,
		boolean enoughToRank) {
}
