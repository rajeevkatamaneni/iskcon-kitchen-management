package org.iskcon.kms.shift;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Edit an open shift (E6-S2). Reminder-offset changes reschedule pending reminder jobs (E6-S6).
 *
 * <p>The same times rule as {@link CreateShiftRequest}: {@code shiftDate} is the date the shift
 * starts, and an {@code endTime} at or before {@code startTime} runs it through midnight (T-146). An
 * ordinary shift can therefore be edited into an overnight one and back, which matters — a temple
 * that has posted "Janmashtami prep, 20:00–22:00" and then realises the offering runs until two in
 * the morning should be able to correct it rather than cancel the shift and empty the roster.
 */
public record UpdateShiftRequest(
		@NotBlank(message = "Give the shift a title.")
		@Size(max = 200, message = "That title is too long.")
		String title,
		@Size(max = 2000, message = "That description is too long.") String description,
		@NotNull(message = "Choose the day of the shift.") LocalDate shiftDate,
		@NotNull(message = "Choose what time the shift starts.") LocalTime startTime,
		@NotNull(message = "Choose what time the shift ends.") LocalTime endTime,
		@Size(max = 300, message = "That location is too long.") String location,
		@Positive(message = "A shift needs room for at least one volunteer.") int capacity,
		List<@Positive(message = "A reminder goes out at least one minute before the shift.") Integer>
				reminderOffsetsMinutes,
		/**
		 * The meal this shift is posted for (D-14), or nothing at all where it is not posted for one.
		 *
		 * <p>{@code mealDate} and {@code mealKind} move together — half a link is a link to nothing,
		 * and is refused with KMS-400125 rather than saved as something that would count toward no
		 * meal while looking deliberate on the screen. {@code mealEventName} is given only where the
		 * meal is a named event, and only alongside the other two.
		 *
		 * <p>Not a meal id, because there is no meal to have one: a meal is a date, a kind and an
		 * event name inferred from the dish rows that share them, and a shift is posted weeks before
		 * any of those rows exist. Nor validated against a planned meal, for the same reason — a
		 * temple finds the hands first and decides the menu later, and a link refused because the
		 * lunch has not been planned yet would make the field unusable in the order it is used.
		 */
		LocalDate mealDate,
		@Size(max = 100, message = "That name is too long.") String mealKind,
		@Size(max = 200, message = "That event name is too long.") String mealEventName) {

	/**
	 * A shift has to have some length, and 20:00 to 20:00 does not say what length (T-146).
	 *
	 * <p>Named as a question about the request rather than about one of the two fields, following the
	 * other cross-field rules in this application ({@code ClosePoRequest.isNamedOutcomeExplained},
	 * {@code UpdatePreferenceRequest.isExactlyOneThing}).
	 *
	 * <p><strong>Why equal times are the one pairing still refused.</strong> Since T-146 an
	 * {@code endTime} at or before {@code startTime} means the shift runs through midnight, which is
	 * the whole point of that task — but equality is ambiguous between a shift of no length at all
	 * and one of twenty-four hours, and nothing in the row can say which the coordinator meant. A
	 * roster cannot act on it either way. The database carries the same rule as a CHECK
	 * ({@code shifts_time_window}, V127) so a caller that bypasses this layer is refused by the row
	 * itself; this is the one the caller reads.
	 *
	 * <p>Bean Validation rather than an error code of its own, which is the established pattern for
	 * "this field is wrong" here: {@code KMS-400001} with a {@code fieldErrors} entry carrying this
	 * sentence. It replaces a bare {@code KMS-500001} — "something went wrong at our end, try again
	 * in a moment" — which blamed us for the caller's typo and offered a next step that could not
	 * work.
	 */
	@AssertTrue(message = "A shift cannot start and end at the same time. For one that runs through the night, give the time it ends the next morning.")
	public boolean isEndTimeDifferentFromStartTime() {
		// Either being absent is @NotNull's failure to report, not this one's: answering false here
		// too would put a second sentence about times on a form whose real fault is an empty box.
		return startTime == null || endTime == null || !startTime.equals(endTime);
	}
}
