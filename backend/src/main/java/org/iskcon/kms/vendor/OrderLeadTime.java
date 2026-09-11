package org.iskcon.kms.vendor;

import java.time.LocalDate;

/**
 * One order's side of the promise: how long its vendor asked for, the last day it could therefore
 * be placed, and — while it is still a draft — where today stands against that day (T-137, D-25).
 *
 * <p>Rajeev dictated the principle rather than the mechanism: <em>"We can't forget the Golden Rule:
 * Hold others to the same standards you want to be held to."</em> A lead time is the vendor's own
 * number, agreed at onboarding and padded by them on purpose, and it binds both sides — we may hold
 * them to a delivery only where we gave them the notice they asked for.
 *
 * <p><strong>This type exists so that the sum is done once.</strong> The same arithmetic —
 * needed-by minus the lead time, compared to today — has to surface on the planner badge, on
 * <em>Mark sent</em>, on the Today dashboard and at the top of the purchase-orders page. Built four
 * times it would quietly disagree four ways, and the failure would not be an exception anybody sees:
 * it would be the planner telling a cook to order by the 13th, the order screen letting it through
 * on the 14th in silence, and the dashboard calling the draft healthy. So {@link LeadTimes#orderBy}
 * and {@link OrderUrgency#on} stay the only implementations, and this record is what carries their
 * answer around.
 *
 * <h2>Every field is null together, and that is the silence Rajeev asked for</h2>
 *
 * <p>An order whose vendor has no recorded lead time for anything on it has <strong>no cutoff</strong>:
 * no nudge, no warning, no exclusion from their score, nothing held against anybody. {@link #NONE}
 * is that state. An order with no needed-by date has no cutoff either — there is no day to count
 * back from — but it does keep {@link #days()}, because what the vendor asked for is still a fact
 * about the order and is still worth stamping on it. Callers ask {@link #tooLate()} and get false
 * in both cases, which is the reading that blames nobody.
 *
 * <p><strong>Unknown is emphatically not two days here</strong>, and that is a deliberate difference
 * from the shopping list. {@link LeadTimes#ASSUMED_LEAD_TIME_DAYS} exists so a temple with nothing
 * recorded still gets a usable order-by date to <em>plan</em> with — a guess that errs towards
 * ordering early costs nobody anything. This type answers a different question: whether we may hold
 * a supplier to a delivery. Judging a vendor against a number we invented for them is precisely
 * what the Golden Rule forbids.
 *
 * @param days     the lead time governing this order — the longest recorded against its vendor for
 *                 the ingredients on it, because the order is only fully deliverable when its
 *                 slowest item is (Rajeev, asked directly, 2026-09-10). Null where nobody has said.
 * @param orderBy  the last day the order could be placed and still arrive: needed-by minus
 *                 {@code days}. Null exactly when {@code days} or the needed-by date is.
 * @param urgency  where today stands against {@code orderBy}, for an order that has not gone out
 *                 yet. <strong>Null once the order has been sent</strong>, and not because the
 *                 answer is unknown: the zone is advice about when to press the button, and on a
 *                 sent order the question has been answered by the pressing. What the order went
 *                 out under is the stamped verdict on the row, not a zone recomputed against
 *                 today's date for ever afterwards.
 */
public record OrderLeadTime(Integer days, LocalDate orderBy, OrderUrgency urgency) {

	/** Nobody has said how long this vendor needs, so there is no promise to hold anybody to. */
	public static final OrderLeadTime NONE = new OrderLeadTime(null, null, null);

	/**
	 * The stance of an order that has not been sent yet: the zone included.
	 *
	 * @param recorded the governing recorded lead time, or null where none was recorded
	 * @param neededBy the date on the order, or null
	 * @param today    the temple's own today — never the server's, for the reason everything else in
	 *                 this application dates itself that way
	 */
	public static OrderLeadTime beforeSending(Integer recorded, LocalDate neededBy, LocalDate today) {
		if (recorded == null) {
			return NONE;
		}
		if (neededBy == null) {
			// The vendor's notice period is still a fact about this order — it is what they agreed
			// to, and it is stamped on the order when it goes out so that a reader afterwards can
			// see what applied. There is simply no day to count back from, so there is no cutoff
			// and no zone: an order with nothing to meet cannot be sent late, and the scorecard
			// counts it aside rather than judging it (VendorPerformanceRow.ordersWithoutNeededBy).
			return new OrderLeadTime(recorded, null, null);
		}
		LocalDate orderBy = LeadTimes.orderBy(neededBy, recorded);
		return new OrderLeadTime(recorded, orderBy, OrderUrgency.on(today, orderBy));
	}

	/**
	 * The stance of an order that has gone out, read back from what was stamped on it.
	 *
	 * <p>No zone: see {@link #urgency}. The order-by date is still worked out, because a person
	 * reading a sent order wants to know what the deadline had been — but it is worked out from the
	 * <em>stamped</em> lead time, never from the vendor's profile as it stands today.
	 */
	public static OrderLeadTime asSent(Integer stamped, LocalDate neededBy) {
		if (stamped == null || neededBy == null) {
			return new OrderLeadTime(stamped, null, null);
		}
		return new OrderLeadTime(stamped, LeadTimes.orderBy(neededBy, stamped), null);
	}

	/**
	 * Whether sending this order now would be asking the vendor for the impossible.
	 *
	 * <p>False when there is no cutoff at all, which is the whole of the no-recorded-lead-time rule:
	 * an order nobody can be late for cannot be sent late either.
	 */
	public boolean tooLate() {
		return urgency == OrderUrgency.TOO_LATE;
	}
}
