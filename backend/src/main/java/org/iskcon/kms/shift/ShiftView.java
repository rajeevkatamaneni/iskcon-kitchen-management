package org.iskcon.kms.shift;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * A volunteer shift (E6-S2) with its live roster counts. {@code signedUpCount} against
 * {@code capacity} is the fill shown on the volunteer card; {@code waitlistCount} is how many are
 * queued behind a full shift.
 */
public record ShiftView(
		UUID id,
		String title,
		String description,
		LocalDate shiftDate,
		LocalTime startTime,
		LocalTime endTime,
		String location,
		int capacity,
		List<Integer> reminderOffsetsMinutes,
		String status,
		String cancelReason,
		int signedUpCount,
		int waitlistCount,
		Instant createdAt,

		/**
		 * The meal this shift was posted for (D-14), or null throughout where it was not posted for
		 * one. The three are all-present or all-absent apart from {@code mealEventName}, which is
		 * null unless the meal is a named event.
		 *
		 * <p>Sent on every row rather than only on linked ones, so a reader never has to tell "this
		 * shift is not linked" apart from "this endpoint does not say".
		 */
		LocalDate mealDate,
		String mealKind,
		String mealEventName) {

	/**
	 * Whether this shift was posted for one named meal, rather than being a general offer of hands.
	 *
	 * <p>The two are counted differently and the distinction is real, not a compatibility hack: a
	 * devotee who says "I can help Saturday morning" is offering hours, and the clock is the right
	 * way to place them; a devotee called in for Janmashtami lunch prep is committed to one meal, and
	 * counting them toward breakfast because breakfast is what is due while they are chopping is
	 * simply wrong.
	 */
	public boolean linkedToAMeal() {
		return mealDate != null;
	}
}
