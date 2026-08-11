package org.iskcon.kms.invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One recorded payment against a vendor invoice (E7-S8). */
public record InvoicePaymentView(
		UUID id,
		LocalDate paidOn,
		BigDecimal amount,
		String method,
		String reference,
		String note,
		String recordedByName,
		Instant createdAt) {
}
