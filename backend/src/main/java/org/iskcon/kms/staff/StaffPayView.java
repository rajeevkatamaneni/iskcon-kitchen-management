package org.iskcon.kms.staff;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Everything the temple knows about what it pays one member of staff (B8).
 *
 * <p>Its own request rather than fields on {@link StaffProfileView}, and that is the whole point.
 * The profile view is shared: the roster at {@code /staff-schedule} builds on it behind
 * {@code MANAGE_STAFF_SCHEDULE}, and a staff member reads their own through {@code /schedule/me}.
 * A salary added there would follow the view into both. Pay lives here, behind
 * {@code MANAGE_STAFF}, which the temple administrator holds and the kitchen manager does not.
 *
 * <p>{@code monthlySalary} is null when no pay has been agreed, and screens must say so in those
 * words rather than printing a zero nobody agreed to.
 *
 * <p>{@code advanceBalance} is advances given minus deductions recovered, both of which are rows we
 * hold. It is arithmetic rather than inference, which is why the termination screen can show it as
 * a hard figure while refusing to guess at salary owed.
 */
public record StaffPayView(
		UUID staffId,
		String fullName,

		/** The temple's own currency (ISO-4217), so a screen never hard-codes a symbol. */
		String currency,

		/** A monthly figure, or null when nothing has been agreed. Never zero standing in for none. */
		BigDecimal monthlySalary,

		/** What is still outstanding across every advance that has not been voided. */
		BigDecimal advanceBalance,

		/**
		 * The most recent salary payment that still stands, or null if there has never been one. A
		 * settlement is deliberately not counted: the termination screen shows this while the
		 * settlement is being typed, and would otherwise answer its own question.
		 */
		StaffPaymentView lastSalaryPayment,

		/** Most recent first. Voided entries are included and marked, never hidden. */
		List<StaffPaymentView> payments,
		List<StaffAdvanceView> advances) {
}
