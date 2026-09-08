package org.iskcon.kms.invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One recorded payment against a vendor invoice (E7-S8). {@code amount} is signed: positive for a
 * payment, negative for a row that undoes an earlier one.
 *
 * <p>The two ends of a reversal (T-010), and the reason there are two rather than a struck flag:
 * {@code invoice_payments} is append-only, so nothing is ever marked. On the compensating row,
 * {@code reverses} names the payment it undoes and {@code reverseReason} says why. On the original,
 * {@code reversedBy} names the row that undid it — worked out here by joining the ledger to itself,
 * so a screen never has to scan the list to find out whether a payment still stands. All three are
 * null on an ordinary payment.
 */
public record InvoicePaymentView(
		UUID id,
		LocalDate paidOn,
		BigDecimal amount,
		String method,
		String reference,
		String note,
		String recordedByName,
		UUID reverses,
		UUID reversedBy,
		String reverseReason,
		Instant createdAt) {
}
