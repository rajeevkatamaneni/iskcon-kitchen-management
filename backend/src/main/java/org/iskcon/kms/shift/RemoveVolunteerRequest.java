package org.iskcon.kms.shift;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Why a coordinator is taking a named volunteer off a roster (T-080), behind
 * {@code MANAGE_VOLUNTEER_SHIFTS}.
 *
 * <p><strong>Two fields, and they do different jobs.</strong> Rajeev's review of 2026-09-08 settles
 * it in one sentence: the coordinator must say why either way, and the volunteer gets the version
 * that does not sting. {@link #reason} is a short structured answer the coordinator picks, safe to
 * show, and it is what goes into the message the volunteer receives. {@link #internalNote} is free
 * text, mandatory, and never leaves the temple — it goes on the audit trail and on the roster.
 *
 * <p>Both are required, and that is the point rather than an oversight in favour of convenience. A
 * coordinator who has to write a private note is a coordinator who has thought about it; a
 * volunteer who reads "the rota changed" is not told they were unreliable. Dropping either half
 * collapses back into one of the two failures this task exists to fix — a removal nobody has to
 * justify, or a justification the person it is about never hears.
 *
 * <p>Requiring them is Bean Validation and nothing more: a missing field answers
 * {@code KMS-400001} with {@code fieldErrors} naming it, which is the same refusal every other form
 * in the product gives, so no error code is allocated for this.
 */
public record RemoveVolunteerRequest(
		@NotNull(message = "Choose why this volunteer is coming off the shift.")
		Reason reason,

		@NotBlank(message = "Say why, in your own words. Only the temple sees this.")
		@Size(max = 1000, message = "That note is too long.")
		String internalNote) {

	/**
	 * The four answers Rajeev named — <em>shift cancelled · no longer needed · rota changed ·
	 * other</em> — and the plain sentence each becomes in the volunteer's message.
	 *
	 * <p>A closed vocabulary rather than free text because this half <em>is sent</em>. Anything a
	 * coordinator could type here would reach a devotee's phone unreviewed, in a moment when they
	 * are already annoyed enough to be taking somebody off a roster; the private note is where that
	 * sentence belongs and is exactly what it is for.
	 *
	 * <p>The wording lives here rather than in {@code NotificationTemplate} so the template stays one
	 * sentence with one hole in it, the way every other template in that enum is, and so the four
	 * phrasings can be read at once next to the four names. They are written to be the end of
	 * "Reason: …", which is why each is a lower-case clause with no full stop.
	 *
	 * <p>{@link #OTHER} is the one that needed a decision and did not get one from Rajeev. The note
	 * it carries is internal and must not be sent, so "other" cannot mean "say nothing" — silence is
	 * the original defect. It renders as a change at the temple: true of every one of these,
	 * accusing nobody, and not a guess at what the coordinator wrote.
	 */
	public enum Reason {
		SHIFT_CANCELLED("the shift was cancelled"),
		NO_LONGER_NEEDED("help is no longer needed for this shift"),
		ROTA_CHANGED("the rota changed"),
		OTHER("a change at the temple");

		private final String volunteerText;

		Reason(String volunteerText) {
			this.volunteerText = volunteerText;
		}

		/** The clause a volunteer reads. Never the note, which this class has no way to reach. */
		public String volunteerText() {
			return volunteerText;
		}
	}
}
