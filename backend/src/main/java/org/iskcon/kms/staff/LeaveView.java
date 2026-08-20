package org.iskcon.kms.staff;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One leave record as every screen reads it (B7): the person's own account page, the approver's
 * queue, and the week grid's read-only marker.
 *
 * <p>It carries the staff member's name rather than making three screens join for it, and it
 * carries the decider's name for the same reason — "approved by" with a UUID beside it is not an
 * answer to the question the page is asking. Neither is a permission risk: a name and a job title
 * are what the roster shows already, and nothing here touches pay, address or PAN.
 */
public record LeaveView(
		UUID id,
		UUID staffProfileId,
		String staffName,
		String jobTitleLabel,

		LeaveType leaveType,
		/** What to print for the type, so the browser never keeps its own copy of the vocabulary. */
		String leaveTypeLabel,

		LocalDate fromDate,
		LocalDate toDate,
		boolean halfDay,
		String reason,

		LeaveStatus status,

		/** Null where the temple recorded this for somebody who holds no login (B7 §4). */
		String requestedByName,
		Instant requestedAt,
		String decidedByName,
		Instant decidedAt,
		String decisionNote) {

	/** How many days this covers, for a list that would otherwise make the reader count. */
	public String durationLabel() {
		if (halfDay) {
			return "Half day";
		}
		long days = toDate.toEpochDay() - fromDate.toEpochDay() + 1;
		return days == 1 ? "1 day" : days + " days";
	}
}
