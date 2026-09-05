package org.iskcon.kms.meal;

import java.time.LocalTime;

/**
 * When to leave the temple for a delivery (E4-S16).
 *
 * <p>It says when to <em>leave</em>, not how long it takes. Rajeev asked for "35-45 minutes of
 * delivery travel time"; the thing a driver can act on is <em>leave the temple by 11:15</em>. So the
 * leave-by time is the answer and the range is the working, and both are computed backwards from the
 * time the guests sit down to eat — which is why an event asks for that time on a delivery and not
 * just the ready-by.
 *
 * <p>The leave-by is taken from the <em>pessimistic</em> end. Arriving early with the food is an
 * inconvenience; arriving after the guests have sat down is the thing this exists to prevent.
 *
 * <p>Unavailable is a first-class answer, not a failure. A temple with no map service, an address the
 * map could not place, a service having a bad minute — each of those is one quiet sentence on the
 * screen and no change whatever to the meal plan. {@code reason} says which sentence.
 *
 * @param available     whether there is an estimate at all.
 * @param leaveBy       the local time to leave the temple, or null.
 * @param optimisticMinutes the kind end of the range.
 * @param pessimisticMinutes the unkind end — what {@code leaveBy} is worked back from.
 * @param guestsEatAt   the time the estimate was worked backwards from, so the screen can show its
 *                      arithmetic rather than assert a number.
 * @param reason        why there is no estimate: {@code NOT_A_DELIVERY}, {@code NO_SERVING_TIME},
 *                      {@code NO_MAP_SERVICE}, {@code ADDRESS_NOT_FOUND} or {@code NO_ROUTE}. Null
 *                      when there is one.
 */
public record TravelEstimate(
		boolean available,
		LocalTime leaveBy,
		Integer optimisticMinutes,
		Integer pessimisticMinutes,
		LocalTime guestsEatAt,
		String reason) {

	public static TravelEstimate unavailable(String reason) {
		return new TravelEstimate(false, null, null, null, null, reason);
	}
}
