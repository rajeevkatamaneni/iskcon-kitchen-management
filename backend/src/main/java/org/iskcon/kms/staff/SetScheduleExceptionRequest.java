package org.iskcon.kms.staff;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

/** Override one date on a staff member's schedule (E6-S1), leaving the template untouched. */
public record SetScheduleExceptionRequest(
		@NotNull LocalDate exceptionDate,
		boolean working,
		LocalTime startTime,
		LocalTime endTime,
		@Size(max = 300) String note) {
}
