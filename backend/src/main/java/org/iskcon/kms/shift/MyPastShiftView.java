package org.iskcon.kms.shift;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * A shift the volunteer served — one that has already happened and that they were still on — for
 * the "Past shifts" list on <em>My shifts</em> (T-429).
 *
 * <p><strong>Why this record exists at all.</strong> {@link MyShiftView} is the same row read
 * forwards: what a volunteer is committed to. Until now that was the only thing the screen could
 * show them, so a devotee who had served six times opened <em>My shifts</em> to an empty page while
 * the coordinator could see her whole month on the roster. The fact that she came was already
 * stored against her name — {@code shift_signups.attended}, since V107 — and there was no endpoint
 * that would return it to the person it is about. Only {@link RosterView}, behind
 * {@code MANAGE_VOLUNTEER_SHIFTS}, could read it.
 *
 * <p><strong>{@code attended} is a {@link Boolean} and the null is the point (V107).</strong> TRUE
 * they came, FALSE they did not, <em>null nobody has said</em>. A shift the coordinator never got
 * round to marking must not read as an absence on the page of the person it would be an absence
 * about — that is the whole reason the column is nullable, and V107's comment says so in those
 * words. It is required-and-nullable rather than optional, on this record and in
 * {@code frontend/lib/api.ts}, so that no reader can forget the third case.
 * {@code attendanceRecordedAt} moves with it; the database carries that pairing as a CHECK.
 *
 * <p><strong>What is deliberately not here.</strong> {@code released_reason} and
 * {@code released_note}: this list is shifts the volunteer was <em>still on</em>
 * ({@code released_at IS NULL}), so a removal is out of scope by construction and belongs to
 * {@link MyReleasedShiftView}, which already answers it. And {@code attendanceCorrectedAt} /
 * {@code attendanceCorrectedByName} from the roster: who inside the temple revised a mark is the
 * coordinator's provenance, not news to the volunteer, who only needs to read the mark that now
 * stands.
 *
 * <p>The component names are the wire contract with {@code MyPastShiftView} in
 * {@code frontend/lib/api.ts} and must stay spelled exactly as they are.
 */
public record MyPastShiftView(
		UUID signupId,
		UUID shiftId,
		String title,
		LocalDate shiftDate,
		LocalTime startTime,
		LocalTime endTime,
		String location,
		String source,
		Instant signedUpAt,
		/** True they came, false they did not, null nobody has said. Never collapse null into false. */
		Boolean attended,
		/** When the mark was made; null exactly when {@code attended} is null (V107's CHECK). */
		Instant attendanceRecordedAt) {
}
