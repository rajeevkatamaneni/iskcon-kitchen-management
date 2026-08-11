package org.iskcon.kms.shift;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * A volunteer shift (E6-S2) with its live roster counts. {@code signedUpCount} against
 * {@code capacity} is the fill shown on the volunteer card; {@code waitlistCount} is how many are
 * queued behind a full shift.
 */
public record ShiftView(
		UUID id,
		String title,
		String description,
		LocalDate shiftDate,
		LocalTime startTime,
		LocalTime endTime,
		String location,
		int capacity,
		List<Integer> reminderOffsetsMinutes,
		String status,
		String cancelReason,
		int signedUpCount,
		int waitlistCount,
		Instant createdAt) {
}
