package org.iskcon.kms.staff;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Ending someone's employment (E6-S8). Never a deletion: the schedule they worked, the shifts they
 * covered and the audit trail that names them all have to survive them leaving.
 *
 * <p>{@code revokeSignIn} decides what happens to their account, and the two cases genuinely
 * differ. Someone who resigns is still a devotee of this temple and should keep signing in as one,
 * so their role drops back to volunteer. Someone dismissed for cause should not, so their account is
 * disabled. The form defaults it from the reason and lets the admin decide.
 */
public record EndEmploymentRequest(

		@NotNull(message = "Say how this employment ended.")
		EmploymentStatus status,

		@NotNull(message = "Enter their last working day.")
		LocalDate lastWorkingDay,

		@Size(max = 1000) String reason,

		/** True disables the account outright; false returns them to being an ordinary devotee. */
		boolean revokeSignIn,

		/**
		 * A ban record to raise against this person, visible to every temple on the platform (B9).
		 *
		 * <p><b>Null in the ordinary case, and the screen is built so that it is.</b> Not every
		 * dismissal raises one of these — most do not — so the option is unticked by default and has
		 * to be chosen deliberately. It rides on this request rather than being a call of its own
		 * because it is a decision made <em>at</em> the dismissal, in the same transaction: an
		 * employment ended without the record the admin asked for is worse than neither, and a record
		 * that can be raised against anybody at any time is a different and much more dangerous
		 * feature than this one.
		 */
		@jakarta.validation.Valid
		org.iskcon.kms.ban.RaiseEmploymentBanRequest ban) {
}
