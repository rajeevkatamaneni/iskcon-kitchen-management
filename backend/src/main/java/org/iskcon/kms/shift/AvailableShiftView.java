package org.iskcon.kms.shift;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * A shift as a volunteer sees it (E6-S3). {@code callerState} tells the UI which action to show:
 * {@code AVAILABLE} (sign up), {@code FULL} (join waitlist), {@code SIGNED_UP} (you're in), or
 * {@code WAITLISTED} (you're queued).
 */
public record AvailableShiftView(
		UUID id,
		String title,
		String description,
		LocalDate shiftDate,
		LocalTime startTime,
		LocalTime endTime,
		String location,
		int capacity,
		int signedUpCount,
		int waitlistCount,
		String callerState) {
}
