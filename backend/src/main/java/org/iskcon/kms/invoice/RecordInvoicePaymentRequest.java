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
		@NotNull LocalDate paidOn,
		@NotNull BigDecimal amount,
		@NotNull PaymentMethod method,
		@Size(max = 100) String reference,
		@Size(max = 500) String note) {

	public enum PaymentMethod { BANK_TRANSFER, UPI, CHEQUE, CASH }
}
