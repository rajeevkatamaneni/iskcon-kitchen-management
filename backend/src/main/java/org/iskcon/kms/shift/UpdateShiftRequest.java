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
		List<@Positive Integer> reminderOffsetsMinutes) {
}
