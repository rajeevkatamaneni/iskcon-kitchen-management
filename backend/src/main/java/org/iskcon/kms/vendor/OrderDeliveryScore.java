package org.iskcon.kms.vendor;

import java.math.BigDecimal;

/**
 * What one purchase order scored on delivery, computed the way the scorecard computes it (T-142).
 *
 * <p><strong>This exists so that the figure can be SHOWN and never edited</strong> (D-26). Rajeev
 * proposed letting an admin adjust a vendor's score when closing a part-delivered order and then
 * ruled against his own proposal: <em>"Let us not let the admin adjust the score. Just show it to
 * them."</em> Showing it is the half that stayed, and this record is that half — a read, with no
 * writer anywhere in the application.
 *
 * <p><strong>It is the same arithmetic, not a second copy.</strong> The percentage here comes from
 * {@code VendorPerformanceService}'s own item query and {@code itemFraction}, narrowed to one
 * order. If it were computed separately, a person closing an order would be shown one number and
 * the scorecard would print another, and both would be defended by somebody.
 *
 * @param percent     the mean of this order's items, each the fraction of it that arrived on or
 *                    before the needed-by day, as a whole percentage. <strong>Null where there is
 *                    nothing to score</strong> — no needed-by date to be late against, or no lines
 *                    at all — because a figure divided by nothing is worse than no figure, which is
 *                    the choice the scorecard already makes for {@code onTimePercent}.
 * @param itemsScored the items behind that percentage: the "of ten" in "eight of ten items".
 * @param itemsOnTime of those, the ones fully there in time. Eight of ten is a different statement
 *                    from a bare 80% — two items missing entirely, or ten items all a fifth short —
 *                    and the person deciding how to close an order needs to see which.
 */
public record OrderDeliveryScore(BigDecimal percent, int itemsScored, int itemsOnTime) {

	/** Nothing to score: no needed-by date, or no lines. */
	static OrderDeliveryScore nothingToScore() {
		return new OrderDeliveryScore(null, 0, 0);
	}
}
