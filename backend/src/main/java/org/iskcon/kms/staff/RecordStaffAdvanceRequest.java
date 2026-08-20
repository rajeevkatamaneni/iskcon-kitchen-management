package org.iskcon.kms.staff;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Recording money handed over ahead of the work (B8).
 *
 * <p>The same fields as a payment, because it is the same act — money left the temple on a date, by
 * a means, with a reference. What differs is the meaning: an advance creates a balance the temple
 * expects back, and later payments repay it through {@link PaymentDeductionRequest}.
 *
 * <p>Cheque or cash only. An advance is by definition handed over outside a payroll run.
 */
public record RecordStaffAdvanceRequest(

		@NotNull(message = "Enter the date the advance was given.")
		LocalDate paidOn,

		@NotNull(message = "Enter the amount of the advance.")
		BigDecimal amount,

		@NotNull(message = "Choose how the advance was given.")
		PaymentMode mode,

		@Size(max = 100, message = "That reference is too long.")
		String reference,

		@Size(max = 1000) String note) {
}
