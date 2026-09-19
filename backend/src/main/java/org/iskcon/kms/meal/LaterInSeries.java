package org.iskcon.kms.meal;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What "Cancel this and every later one" would cancel, read before the planner confirms (T-307).
 *
 * <p>The confirmation names these, and sends their ids back as {@code expectedMealIds}; the cancel
 * refuses (KMS-400179) if the set has changed since, so nobody cancels a meal they were not shown.
 *
 * @param seriesId         the series.
 * @param later            every later occurrence that would be cancelled, by date: after this meal,
 *                         not before today at the temple, still with a dish to cook, nothing cooked
 *                         and not recorded. Past, cooked, recorded and already-cancelled occurrences
 *                         are never in it and never touched.
 * @param lastDate         the last of {@code later}, or null where there is none.
 * @param volunteersToTell how many volunteers would be told — signed up or waitlisted on this meal's
 *                         shift and on every later one's. The same people the cancellation messages
 *                         go to.
 */
public record LaterInSeries(UUID seriesId, List<Later> later, LocalDate lastDate, int volunteersToTell) {

	/**
	 * One later occurrence.
	 *
	 * @param edited             true where someone changed this occurrence on its own after it was
	 *                           repeated, so the confirmation can say so before it is cancelled.
	 * @param volunteersSignedUp how many volunteers are signed up on its shift; 0 where it has none.
	 */
	public record Later(UUID mealId, LocalDate planDate, boolean edited, int volunteersSignedUp) {
	}
}
