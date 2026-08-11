package org.iskcon.kms.shift;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** A shift the volunteer is waitlisted for (E6-S5), with their current queue position. */
public record MyWaitlistView(
		UUID shiftId,
		String title,
		LocalDate shiftDate,
		LocalTime startTime,
		LocalTime endTime,
		String location,
		int position,
		Instant joinedAt) {
}
