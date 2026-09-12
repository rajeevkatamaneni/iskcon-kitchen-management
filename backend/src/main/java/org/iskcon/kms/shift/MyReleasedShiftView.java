package org.iskcon.kms.shift;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * A shift the volunteer came off in the last week without choosing to (T-149), for the "taken off"
 * list on My Shifts.
 *
 * <p>Two ways in, and both end in the same row shape because the volunteer asks the same question
 * of each: <em>which shift, and why am I no longer on it?</em> A coordinator took them off it, in
 * which case {@code reason} is what the coordinator picked; or the shift was cancelled with them
 * still on it, in which case {@code reason} is {@code SHIFT_CANCELLED} and {@code releasedAt} is
 * the cancellation.
 *
 * <p><strong>What is deliberately not here: the coordinator's internal note.</strong> V124 made
 * that note mandatory beside every removal and made it internal — it reaches the audit trail and
 * the roster and nothing else — and this is the first read of a removal that is addressed to the
 * person removed. So the note is not a component of this record and the query that fills it never
 * selects the column. Said out loud, as {@code notifyRemoval} says it, because the proof is an
 * absence: {@code MyReleasedShiftsIT} pins the exact JSON field set rather than looking for a field
 * that is not there.
 *
 * <p>The component names are the wire contract with {@code MyReleasedShiftView} in
 * {@code frontend/lib/api.ts} and must stay spelled exactly as they are.
 */
public record MyReleasedShiftView(
		UUID signupId,
		UUID shiftId,
		String title,
		LocalDate shiftDate,
		LocalTime startTime,
		LocalTime endTime,
		String location,
		/** When they came off it: the coordinator's removal, or the shift's cancellation. */
		Instant releasedAt,
		/** Serialises as the enum's name, which is the four-value union the screen switches on. */
		RemoveVolunteerRequest.Reason reason) {
}
