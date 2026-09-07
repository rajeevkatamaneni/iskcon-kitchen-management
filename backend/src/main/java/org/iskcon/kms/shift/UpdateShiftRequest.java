package org.iskcon.kms.shift;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Edit an open shift (E6-S2). Reminder-offset changes reschedule pending reminder jobs (E6-S6). */
public record UpdateShiftRequest(
		@NotBlank @Size(max = 200) String title,
		@Size(max = 2000) String description,
		@NotNull LocalDate shiftDate,
		@NotNull LocalTime startTime,
		@NotNull LocalTime endTime,
		@Size(max = 300) String location,
		@Positive int capacity,
		List<@Positive Integer> reminderOffsetsMinutes,
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
		@Size(max = 100) String mealKind,
		@Size(max = 200) String mealEventName) {
}
