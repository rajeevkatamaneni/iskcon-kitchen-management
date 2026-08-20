package org.iskcon.kms.staff;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Recording that a member of staff was paid (B8).
 *
 * <p>The amount is the <em>gross</em>. What they received is that minus the advances repaid out of
 * it, which is why the deductions are part of the same request: they are one act at the desk, and
 * recording the payment first and the docking afterwards would leave a window where the advance
 * balance is wrong on the screen the admin is looking at.
 *
 * <p>Nothing here is worked out for the admin. There is no pay period, no month being settled and no
 * arrears — the temple asked for a record of what it paid, not for payroll.
 *
 * <p>{@code amount} carries no {@code @Positive} deliberately: zero and negative amounts have their
 * own plain-language failure (KMS-4007), which is more useful than the generic one.
 */
public record RecordStaffPaymentRequest(

		@NotNull(message = "Enter the date of the payment.")
		LocalDate paidOn,

		@NotNull(message = "Enter the amount paid.")
		BigDecimal amount,

		@NotNull(message = "Choose how they were paid.")
		PaymentMode mode,

		/** The cheque number or the payroll reference. Required unless the payment was cash. */
		@Size(max = 100, message = "That reference is too long.")
		String reference,

		@NotNull(message = "Say whether this is salary or a final settlement.")
		PaymentPurpose purpose,

		@Size(max = 1000) String note,

		/** Advances repaid out of this payment. Empty for a payment with nothing docked. */
		@Valid List<PaymentDeductionRequest> deductions) {

	/** Never null downstream: "no deductions" and "an empty list" are the same thing here. */
	public List<PaymentDeductionRequest> deductionsOrEmpty() {
		return deductions == null ? List.of() : deductions;
	}
}
