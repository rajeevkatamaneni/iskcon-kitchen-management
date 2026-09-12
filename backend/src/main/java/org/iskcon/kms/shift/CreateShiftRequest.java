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
 * Post a volunteer shift (E6-S2). {@code reminderOffsetsMinutes} may be omitted for the default
 * single 24h (1440-minute) reminder; each offset is minutes before the shift start.
 *
 * <p><strong>{@code shiftDate} is the date the shift STARTS (T-146).</strong> An {@code endTime} at
 * or before {@code startTime} means the shift runs through midnight and ends the next morning —
 * 20:00 to 02:00 is the Janmashtami midnight offering, and it is six hours long. That used to be
 * refused by the database with {@code KMS-500001}, "something went wrong at our end, try again in a
 * moment", which blamed us for a reasonable request and gave advice that could never work.
 * {@code org.iskcon.kms.shift.ShiftWindow} is the one place that rule is written in Java.
 */
public record CreateShiftRequest(
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
