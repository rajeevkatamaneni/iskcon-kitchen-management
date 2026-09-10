package org.iskcon.kms.invoice;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Records a payment made to a vendor outside the app (E7-S8). A positive amount is a payment; a
 * negative amount is a compensating correction of an earlier one.
 */
public record RecordInvoicePaymentRequest(
		@NotNull(message = "Enter the date of the payment.") LocalDate paidOn,
		@NotNull(message = "Enter the amount paid.") BigDecimal amount,
		@NotNull(message = "Choose how the vendor was paid.") PaymentMethod method,
		@Size(max = 100, message = "That reference is too long.") String reference,
		@Size(max = 500, message = "That note is too long.") String note) {

	public enum PaymentMethod { BANK_TRANSFER, UPI, CHEQUE, CASH }
}
