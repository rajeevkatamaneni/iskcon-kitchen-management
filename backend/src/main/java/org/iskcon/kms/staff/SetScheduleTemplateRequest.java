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
public record SetScheduleTemplateRequest(
		@NotEmpty(message = "A week needs at least one day on it.") @Valid List<Entry> days) {

	public record Entry(
			@Min(value = 1, message = "A day of the week is a number from 1 to 7.")
			@Max(value = 7, message = "A day of the week is a number from 1 to 7.")
			int dayOfWeek,
			boolean working,
			LocalTime startTime,
			LocalTime endTime) {
	}
}
