package org.iskcon.kms.invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A captured vendor invoice (E5-S8). {@code expectedValue} and {@code variance} are informational and
 * present only when the PO's lines carry prices — the difference between what was invoiced and what
 * the received quantities would cost. {@code overdue} is derived: PENDING and past its due date.
 *
 * <p>{@code voidedAt} and {@code voidReason} are the mark left by striking a bill that was never owed
 * (T-010), null on every invoice that still stands. {@code creditedAmount} is the total of the credit
 * notes recorded against it and is {@code 0} rather than null when there are none — "no credits" and
 * "not told" must not read alike on a screen that subtracts it from the amount.
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
		Instant voidedAt,
		String voidReason,
		BigDecimal creditedAmount,
		Instant createdAt) {
}
