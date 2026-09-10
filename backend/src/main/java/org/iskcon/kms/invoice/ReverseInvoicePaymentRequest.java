package org.iskcon.kms.invoice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Undoing a payment recorded in error (T-010) — a bounced cheque, a mistyped amount, a payment
 * entered against the wrong bill.
 *
 * <p>Reverse rather than void, and the name is the point: {@code invoice_payments} is append-only,
 * so there is no row to mark. The reason travels on the compensating entry the server appends.
 */
public record ReverseInvoicePaymentRequest(
		@NotBlank(message = "Say why the payment is being reversed.")
		@Size(max = 500, message = "That reason is too long.")
		String reason) {
}
