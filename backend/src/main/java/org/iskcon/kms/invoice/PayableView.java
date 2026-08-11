package org.iskcon.kms.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An unpaid invoice on the payables view (E7-S8), with its outstanding balance and aging bucket
 * ({@code CURRENT}, {@code DUE_1_30}, {@code OVERDUE_31_PLUS}).
 */
public record PayableView(
		UUID invoiceId,
		String invoiceNumber,
		String vendorName,
		BigDecimal amount,
		BigDecimal paidToDate,
		BigDecimal outstanding,
		LocalDate dueDate,
		String agingBucket) {
}
