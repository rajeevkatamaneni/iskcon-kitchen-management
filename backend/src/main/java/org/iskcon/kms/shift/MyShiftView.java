package org.iskcon.kms.shift;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** An upcoming shift the volunteer is signed up for (E6-S3), for the My Shifts view. */
public record MyShiftView(
		UUID signupId,
		UUID shiftId,
		String title,
		LocalDate shiftDate,
		LocalTime startTime,
		LocalTime endTime,
		String location,
		String source,
		Instant signedUpAt) {
}
