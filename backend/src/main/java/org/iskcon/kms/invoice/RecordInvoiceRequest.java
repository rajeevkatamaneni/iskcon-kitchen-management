package org.iskcon.kms.invoice;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A vendor invoice being captured, itemised (stage 6: R-INV-1..6, T-271).
 *
 * <p>What changed from the E5-S8 shape, and why:
 *
 * <ul>
 *   <li>{@code amount} is gone. The bill's own <em>grand total</em> is typed instead, and becomes the
 *       invoice's amount, so every figure that already sums {@code vendor_invoices.amount} (payables,
 *       the pay status, the giving page's cost per plate) goes on reading what the temple owes.</li>
 *   <li>{@code scanRef} is gone. The copy of the bill is an upload ({@code billAttachmentId}, from
 *       {@code POST /vendor-invoices/bill-uploads}) and it is required (R-INV-2); the service claims it
 *       in the transaction that saves the invoice, so a refused save leaves the upload unclaimed.</li>
 *   <li>{@code purchaseOrderId} is gone. An invoice bills <em>deliveries</em> ({@code receiptIds}); the
 *       server sets the order itself when every billed delivery is on one order. No deliveries is a
 *       direct invoice, whose lines are typed by hand.</li>
 *   <li>The sub total is never sent. The server sums the lines and refuses with {@code KMS-400168}
 *       unless Sub total + GST + Other charges − Discount = Grand total (R-INV-5).</li>
 * </ul>
 *
 * <p>{@code description} is optional on a direct invoice too, now that its lines say what was bought
 * (conductor's ruling for T-271, 2026-09-19).
 */
public record RecordInvoiceRequest(
		@NotNull(message = "Choose a vendor.") UUID vendorId,
		@Size(max = 500, message = "That description is too long.") String description,
		@NotBlank(message = "Enter the number printed on the vendor's bill.")
		@Size(max = 100, message = "That number is too long.")
		String invoiceNumber,
		@NotNull(message = "Enter the date on the bill.") LocalDate invoiceDate,
		LocalDate dueDate,
		List<UUID> receiptIds,
		@NotEmpty(message = "Add at least one item from the bill.") List<@Valid InvoiceLineInput> lines,
		@NotNull(message = "Enter the GST on the bill, or 0.")
		@PositiveOrZero(message = "GST can't be less than zero.")
		BigDecimal gstAmount,
		@NotNull(message = "Enter the other charges on the bill, or 0.")
		@PositiveOrZero(message = "Other charges can't be less than zero.")
		BigDecimal otherCharges,
		@Size(max = 500, message = "That note is too long.") String otherChargesNote,
		@NotNull(message = "Enter the discount on the bill, or 0.")
		@PositiveOrZero(message = "A discount can't be less than zero.")
		BigDecimal discount,
		@NotNull(message = "Enter the grand total on the bill.")
		@Positive(message = "A bill has to be for more than zero.")
		BigDecimal grandTotal,
		// The standard required message, as the form's own check words it (formMessages.required).
		@NotNull(message = "Copy of the bill is required.") UUID billAttachmentId) {
}
