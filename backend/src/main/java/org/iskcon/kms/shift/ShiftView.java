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
 *
 * <p>{@code signedUpCount} is also what the "times changed" warning reads before a save (D-27,
 * answer 6): <em>"3 volunteers are signed up. They'll be told the new times."</em> It counts the
 * people who will actually be told — signups not released — and not the waitlist, who are not.
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
		 * The one meal this shift is for (D-27), or null for a shift that is not for a meal.
		 *
		 * <p>Rajeev: <em>"a shift is unambiguisloy linked to ONE and ONLY one meal."</em> Until D-27 a
		 * shift remembered its meal by copying the meal's date, kind and event name as text, and the
		 * count matched them back by folding case — the arrangement he ruled out with <em>"identifying
		 * things by text is a terrible idea and one that WILL fail eventually."</em> The id is now the
		 * link, and a foreign key holds it.
		 */
		UUID mealId,

		/**
		 * The meal's kind as the temple names it today, and its event name where it is an event — both
		 * read through the foreign key at the moment of reading, never stored on the shift. They are
		 * for the label on the Volunteer shifts list (<em>"For Lunch, 15 September"</em>) and nothing
		 * matches on them. A kind renamed in Settings is therefore renamed here too, with no cascade.
		 *
		 * <p>Null where {@code mealId} is null; {@code mealEventName} is also null for a meal that is
		 * not an event. Sent on every row, so a reader never has to tell "this shift is not for a meal"
		 * apart from "this endpoint does not say". The date in the label is {@code shiftDate}: a meal
		 * shift's date always comes from its meal (D-27, answer 4).
		 */
		String mealKind,
		String mealEventName) {

	/**
	 * Whether this shift is for one meal, rather than being a general offer of hands.
	 *
	 * <p>The two are counted differently and the distinction is real, not a compatibility hack: a
	 * devotee who says "I can help Saturday morning" is offering hours, and the clock is the right
	 * way to place them; a devotee called in for Janmashtami lunch prep is committed to one meal, and
	 * counting them toward breakfast because breakfast is what is due while they are chopping is
	 * simply wrong.
	 */
	public boolean linkedToAMeal() {
		return mealId != null;
	}
}
