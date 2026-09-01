package org.iskcon.kms.staff;

import java.time.Instant;
import java.util.UUID;

/**
 * One conduct note as it is read (E6-S16): who wrote it, when, and what they wrote.
 *
 * <p>Three facts, and there is no fourth to serve. No severity, no category, no note type, no
 * acknowledgement — see the header of {@code V84__staff_conduct_notes.sql} for why each of those was
 * refused rather than merely not built yet.
 *
 * <p>{@code authorName} is resolved for display because a note whose author is a UUID is a note
 * nobody can attribute. It is the author's name as their user record holds it now; the note itself
 * never changes, and neither does who wrote it.
 */
public record StaffConductNoteView(
		UUID id,
		String body,
		UUID authorUserId,
		String authorName,
		Instant createdAt) {
}
