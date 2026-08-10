package org.iskcon.kms.invoice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A vendor invoice being captured (E5-S8). Supply {@code purchaseOrderId} for the normal case; leave
 * it null and give a {@code description} for a direct (no-PO) cash-market purchase — the service
 * enforces exactly one of those shapes.
 */
public record RecordInvoiceRequest(
		@NotNull UUID vendorId,
		UUID purchaseOrderId,
		@Size(max = 500) String description,
		@NotBlank @Size(max = 100) String invoiceNumber,
		@NotNull LocalDate invoiceDate,
		@NotNull @Positive BigDecimal amount,
		LocalDate dueDate,
		@Size(max = 500) String scanRef) {
}
