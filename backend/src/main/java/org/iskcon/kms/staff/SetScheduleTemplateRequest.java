package org.iskcon.kms.staff;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.time.LocalTime;
import java.util.List;

/**
 * Sets the whole weekly template at once (E6-S1) — the wireframe edits the grid as a unit. Each entry
 * is one weekday; a working day carries a time range, a day off carries none.
 */
public record SetScheduleTemplateRequest(@NotEmpty @Valid List<Entry> days) {

	public record Entry(
			@Min(1) @Max(7) int dayOfWeek,
			boolean working,
			LocalTime startTime,
			LocalTime endTime) {
	}
}
