package org.iskcon.kms.staff;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * The resolved schedule grid for a week (E6-S1): one row per active staff member, seven resolved
 * days each. A resolved day is the exception for that date if one exists, otherwise the template for
 * its weekday — so the grid reads correctly even across a month boundary.
 */
public record WeekScheduleView(LocalDate weekStart, List<StaffWeek> staff) {

	public record StaffWeek(
			UUID staffProfileId,
			UUID userId,
			String fullName,
			String designation,
			List<ResolvedDay> days) {
	}

	public record ResolvedDay(
			LocalDate date,
			int dayOfWeek,
			boolean working,
			LocalTime startTime,
			LocalTime endTime,
			boolean fromException) {
	}
}
