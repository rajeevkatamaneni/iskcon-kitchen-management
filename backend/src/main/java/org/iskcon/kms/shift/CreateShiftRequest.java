package org.iskcon.kms.shift;

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
}
