package org.iskcon.kms.invoice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * A credit note the vendor has issued against a bill that stands (T-010) — a short delivery, a
 * damaged sack, a price agreed down after the invoice was raised.
 *
 * <p>Positive: the amount by which the bill goes down. A negative "credit" would be a second
 * invoice wearing the wrong name, and the database refuses one too.
 */
public record CreditInvoiceRequest(
		@NotNull @Positive BigDecimal amount,
		@NotBlank @Size(max = 500) String reason) {
}
