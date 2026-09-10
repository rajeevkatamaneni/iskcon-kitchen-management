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
		@NotNull(message = "Choose a vendor.") UUID vendorId,
		UUID purchaseOrderId,
		@Size(max = 500, message = "That description is too long.") String description,
		@NotBlank(message = "Enter the number printed on the vendor's bill.")
		@Size(max = 100, message = "That number is too long.")
		String invoiceNumber,
		@NotNull(message = "Enter the date on the bill.") LocalDate invoiceDate,
		@NotNull(message = "Enter the amount on the bill.")
		@Positive(message = "A bill has to be for more than zero.")
		BigDecimal amount,
		LocalDate dueDate,
		@Size(max = 500, message = "That reference is too long.") String scanRef) {
}
