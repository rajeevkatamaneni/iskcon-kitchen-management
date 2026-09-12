/**
 * Phone numbers the way people type them, and the way the server and Firebase need them (T-157).
 *
 * <p>The error written for a malformed number, KMS-400003, gives "+91 98765 43210" as its example —
 * and until this file existed that example was refused by every box it applied to, because the
 * boxes trimmed the ends and kept the spaces in the middle while the rule allows none. The example
 * was right about how a number is written; the boxes were wrong to hold the spacing against it.
 *
 * <p>So every box whose number is held to E.164 — on the server by the nine fields carrying
 * `@Pattern("^\+[1-9][0-9]{7,14}$")`, or by Firebase's `signInWithPhoneNumber`, which requires the
 * same shape — sends what {@link normalizePhone} returns. The server removes exactly the same
 * characters in `PhoneNumberDeserializer`, so what a box accepts here and what the API stores are the
 * same number, and a screen that forgets to call this is still saved by the server. Firebase has no
 * such second chance, which is why the two sign-in screens are the ones this matters most for.
 *
 * <p>Boxes whose numbers are deliberately not E.164 — a kitchen's contact number, where an internal
 * extension is a real answer, or a donor's phone — do not use this, and should not start to.
 */

/**
 * Characters that separate digits and never stand for one: whitespace of every kind (including the
 * non-breaking space autofill brings), dashes (a phone keyboard turns a hyphen into an en dash), and
 * invisible format characters (zero-width spaces, the byte-order mark, and the direction marks
 * Android wraps around a copied contact). Keep in step with `PhoneNumberDeserializer.SEPARATORS`.
 *
 * <p>Brackets and dots are deliberately not here. A bracket usually holds a trunk "0" that has to be
 * dropped once the country code is written — "+91 (0) 80 2345 6789" — and removing the brackets
 * while keeping the 0 would produce a number that passes the rule and rings nobody. Dots are not how
 * anyone writes an Indian number.
 */
const SEPARATORS = /[\s\p{Z}\p{Pd}\p{Cf}]/gu;

/** The rule every E.164 field on the server declares, character for character. */
const E164 = /^\+[1-9][0-9]{7,14}$/;

/**
 * The number with its separators removed, and nothing else changed.
 *
 * <p>"Nothing else" is the point. The provisioning screen's old version kept the plus and the digits
 * and threw away everything else, so "+91 98765 4321X" lost its X and became a well-formed number
 * belonging to somebody else. Here the letter stays, the number is refused, and the person is told.
 */
export function normalizePhone(value: string): string {
  return value.replace(SEPARATORS, "");
}

/** Whether a typed number, once its separators are gone, is one the server and Firebase accept. */
export function isE164(value: string): boolean {
  return E164.test(normalizePhone(value));
}
