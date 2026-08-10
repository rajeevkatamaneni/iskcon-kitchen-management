package org.iskcon.kms.invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A captured vendor invoice (E5-S8). {@code expectedValue} and {@code variance} are informational and
 * present only when the PO's lines carry prices — the difference between what was invoiced and what
 * the received quantities would cost. {@code overdue} is derived: PENDING and past its due date.
 */
public record VendorInvoiceView(
		UUID id,
		UUID vendorId,
		String vendorName,
		UUID purchaseOrderId,
		String poNumber,
		boolean direct,
		String description,
		String invoiceNumber,
		LocalDate invoiceDate,
		BigDecimal amount,
		LocalDate dueDate,
		String scanRef,
		InvoiceStatus status,
		BigDecimal expectedValue,
		BigDecimal variance,
		boolean overdue,
		Instant createdAt) {
}
