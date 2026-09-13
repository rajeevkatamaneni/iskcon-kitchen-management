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

/**
 * An Indian mobile number's ten digits: they start 6, 7, 8 or 9, optionally after a trunk "0" or
 * after "91" or "+91". `[0-9]` rather than `\d`, to match the server's pattern character for character.
 */
const INDIAN_MOBILE = /^(?:\+91|91|0)?([6-9][0-9]{9})$/;

/**
 * A donor's phone as the counter saves it (T-186): "+91" and ten digits when what was typed can only
 * be an Indian mobile number, and otherwise exactly what was typed, trimmed.
 *
 * <p>The server applies the same rule in `donation/CounterPhone.java` and is the authority: this copy
 * exists so the screen can tell the person recording the gift what was saved ("Saved as +91 98765
 * 43210") before they walk away. The two are held to one list of inputs by `phone.test.ts` and
 * `CounterPhoneTest`.
 *
 * <p>Only the unambiguous shapes are rewritten. A landline, a foreign number, a short number, a
 * bracketed "(0)" or a letter is left alone, because a wrong guess stores a well-formed number that
 * rings somebody else, and My donations would hand that somebody the receipt. This is why it is not
 * `normalizePhone`: that one removes separators from a number already held to E.164, and a counter
 * gift's phone is deliberately not held to anything.
 *
 * <p>A blank box is "", which the screen already sends as no phone.
 */
export function normalizeIndianMobile(value: string): string {
  const trimmed = value.trim();
  const mobile = INDIAN_MOBILE.exec(normalizePhone(trimmed));
  return mobile ? `+91${mobile[1]}` : trimmed;
}

/**
 * A saved counter phone written the way it is read aloud: "+919876543210" as "+91 98765 43210".
 * Anything that is not in that form is shown exactly as it was saved, because it was saved as typed.
 */
export function savedPhoneForDisplay(saved: string): string {
  const mobile = /^\+91([6-9][0-9]{4})([0-9]{5})$/.exec(saved);
  return mobile ? `+91 ${mobile[1]} ${mobile[2]}` : saved;
}
