package org.iskcon.kms.staff;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Taking somebody back on (T-014) — the inverse of {@link EndEmploymentRequest}.
 *
 * <p>It exists because ending an employment was, until now, irreversible <em>and</em> locked the
 * record in the same instant: {@code requireStillEmployed} guards both {@code endEmployment} and
 * {@code update}, so a misclick on the termination form could not even be corrected. There was no
 * way back at all.
 *
 * <p>Two fields carry the whole of what the server cannot work out for itself.
 *
 * <p><b>{@code systemAccess} is asked for, never inferred.</b> Ending an employment either disables
 * the account outright or drops the person back to being an ordinary devotee, and it stores nothing
 * anywhere about what their access had been beforehand — there is no prior value to restore. So the
 * admin says what they come back as, exactly as the hire form does. It is deliberately <em>not</em>
 * {@code @NotNull}: null is a real and common answer, meaning they return with no login, which is
 * ordinary for a cook. A field lost in transit therefore arrives as "no access", which is the
 * direction a lost field should fail in — a dropped value grants nothing rather than granting
 * everything the person last held.
 *
 * <p><b>{@code dateOfRejoining} is required for the reason {@code lastWorkingDay} is.</b> An admin
 * recording a reinstatement a week after it happened means the day it happened, and a server clock
 * does not know that. It is not stored on the profile and deliberately so — see the note on
 * {@code StaffEmploymentService.reinstate}, which explains why a reinstatement belongs in the audit
 * trail as an event rather than on the row as an attribute.
 */
public record ReinstateStaffRequest(

		@NotNull(message = "Enter the day they came back.")
		LocalDate dateOfRejoining,

		/** What they come back with. Null means no login at all, and is not an omission. */
		SystemAccess systemAccess,

		@Size(max = 1000, message = "That reason is too long.")
		String reason) {
}
