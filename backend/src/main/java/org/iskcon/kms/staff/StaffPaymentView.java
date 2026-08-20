package org.iskcon.kms.staff;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One payment to a member of staff (B8), with what was recovered from it.
 *
 * <p>{@code net} is {@code gross} minus {@code deducted} and is computed on the way out rather than
 * stored, so the figure on the screen can never disagree with the deduction rows it claims to
 * summarise.
 *
 * <p>A voided payment is still here — nothing is deleted — and carries the moment it was struck.
 * Every total ignores it.
 */
public record StaffPaymentView(
		UUID id,
		LocalDate paidOn,

		/** What the payment was before anything was recovered from it. */
		BigDecimal gross,
		/** The advances this payment repaid, added up. */
		BigDecimal deducted,
		/** What the person actually received: gross minus deducted. */
		BigDecimal net,

		PaymentMode mode,
		String modeLabel,
		String reference,
		PaymentPurpose purpose,
		String purposeLabel,
		String note,

		/** Who wrote it down, for the admin reading the history rather than the audit log. */
		String recordedByName,
		/** Null for a payment that stands; otherwise when it was struck. */
		Instant voidedAt,

		List<Deduction> deductions) {

	/** One advance repaid by this payment. */
	public record Deduction(UUID advanceId, LocalDate advancePaidOn, BigDecimal amount) {
	}

	public boolean isVoided() {
		return voidedAt != null;
	}
}
