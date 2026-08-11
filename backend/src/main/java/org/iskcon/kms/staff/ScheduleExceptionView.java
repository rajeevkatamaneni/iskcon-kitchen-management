package org.iskcon.kms.staff;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** A per-date override of the template (E6-S1): a swapped shift or one-off day off. */
public record ScheduleExceptionView(
		UUID id,
		LocalDate exceptionDate,
		boolean working,
		LocalTime startTime,
		LocalTime endTime,
		String note) {
}
