package org.iskcon.kms.staff;

import jakarta.validation.constraints.Size;

/**
 * Adding one conduct note (E6-S16). The words, and nothing else.
 *
 * <p>Everything else the record holds is decided here rather than sent: the author is the verified
 * signed-in user, the timestamp is the database's, and the temple comes from the token. None of the
 * three is a field a caller could set, which is what makes the note attributable at all.
 *
 * <p>The ceiling matches the database's {@code staff_conduct_note_body_bounded} constraint, so an
 * over-long note is refused as a field the form can highlight rather than as a failed write.
 * Emptiness is checked in the service, because a note of nothing but spaces passes {@code @Size} and
 * would be permanent (KMS-4012).
 */
public record AddConductNoteRequest(@Size(max = 4000) String body) {
}
