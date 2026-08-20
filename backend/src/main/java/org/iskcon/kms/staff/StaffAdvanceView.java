package org.iskcon.kms.staff;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One advance to a member of staff, and how much of it has come back (B8).
 *
 * <p>{@code recovered} is the sum of the deductions recorded against this advance on payments that
 * still stand, and {@code outstanding} is the remainder. Neither is stored: a balance column would
 * be one more thing that can quietly stop agreeing with the entries beneath it, and the entries are
 * what an administrator is actually asked to defend.
 */
public record StaffAdvanceView(
		UUID id,
		LocalDate paidOn,
		BigDecimal amount,

		/** Docked from later payments so far. */
		BigDecimal recovered,
		/** {@code amount} minus {@code recovered} — what the temple is still owed on this one. */
		BigDecimal outstanding,

		PaymentMode mode,
		String modeLabel,
		String reference,
		String note,

		String recordedByName,
		Instant voidedAt) {

	public boolean isVoided() {
		return voidedAt != null;
	}
}
