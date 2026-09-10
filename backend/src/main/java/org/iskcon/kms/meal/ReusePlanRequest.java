package org.iskcon.kms.meal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/**
 * Reusing a stretch of plan somebody already made, or one day of it (2026-09-05).
 *
 * <p>It replaces "Duplicate last week", which could do exactly one thing — last week onto this
 * week. Rajeev refused to build a fifteen-day planner for the temple that asked for one, on the
 * grounds that this is an application for a thousand temples buying on every cycle there is; the
 * copy tool was week-shaped in precisely the way he refused to let the planner be. A temple on a
 * fortnightly cycle could copy seven of its fifteen days and hand-plan the other eight.
 *
 * <p><strong>One day is not a special case.</strong> {@code days = 1} is how the festival copy
 * works — last year's Janmashtami onto this year's. It cannot be offset arithmetic, because the
 * date moves with the Vaishnava calendar: Janmashtami 2027 is eleven days from where 2026 fell. So
 * both ends are picked by hand and the request carries no notion of a year.
 *
 * @param sourceStart the first day to read from.
 * @param days        how many days to read, starting there.
 * @param targetStart the day the first of them lands on. Everything after it follows in order, so a
 *                    gap in the source is a gap in the target rather than a day pulled forward.
 * @param mealKinds   which kinds to bring, by name, as found in the window. Null means all of the
 *                    main meals and nothing else — the safe reading of "just copy it".
 * @param eventNames  which events to bring, by name. Null means none: an event is a thing that was
 *                    arranged, and arranging it again is a decision somebody makes rather than a
 *                    default they inherit.
 */
public record ReusePlanRequest(
		@NotNull(message = "Choose the first day to copy from.") LocalDate sourceStart,

		/**
		 * Capped at 62 days. Two months covers every buying cycle anybody has described, and the cap
		 * is what stops a mistyped figure walking a copy across a year of somebody's plan.
		 */
		@Min(value = 1, message = "Copy between 1 and 62 days at a time.")
		@Max(value = 62, message = "Copy between 1 and 62 days at a time.")
		int days,

		@NotNull(message = "Choose the day the copy should start on.") LocalDate targetStart,
		List<String> mealKinds,
		List<String> eventNames) {
}
