package org.iskcon.kms.ban;

import jakarta.validation.constraints.Size;

/**
 * Taking a ban back (B9).
 *
 * <p>Retraction is not a delete. The record stays on file with the retraction on it, and stops
 * appearing at a hire from that moment. That is deliberate and it carries real weight: because the
 * subject is never shown the reason in the app, retraction, the ten-year fade and the raising
 * temple's name being on every finding are between them the <em>whole</em> of the error correction
 * for this feature. A retraction that erased the record would erase the evidence that a wrong entry
 * had ever been made, which is the opposite of correcting it.
 *
 * @param reason why it was taken back. Optional — a temple correcting its own mistake should not have
 *               to justify itself before it is allowed to
 */
public record RetractEmploymentBanRequest(@Size(max = 1000) String reason) {
}
