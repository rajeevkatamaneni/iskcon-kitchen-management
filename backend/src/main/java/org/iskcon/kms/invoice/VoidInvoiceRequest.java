package org.iskcon.kms.invoice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Striking a bill that should never have been recorded (T-010).
 *
 * <p>The reason is required and not optional. Whoever finds this invoice missing from the payables
 * queue next month has only these words to tell them whether the bill was a duplicate, a vendor's
 * mistake, or something the temple is still arguing about — and the mark is never overwritten.
 */
public record VoidInvoiceRequest(@NotBlank @Size(max = 500) String reason) {
}
