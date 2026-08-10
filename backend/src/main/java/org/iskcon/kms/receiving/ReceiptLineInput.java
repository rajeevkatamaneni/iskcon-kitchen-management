package org.iskcon.kms.receiving;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One line of a delivery against a PO line (E5-S6): how much arrived good, how much was rejected and
 * why, plus the food-safety fields carried onto the received batch. At least one of received or
 * rejected must be positive, and a rejection must name a reason — both enforced in the service and
 * by CHECK constraints.
 */
public record ReceiptLineInput(
		@NotNull UUID poLineId,
		@NotNull @PositiveOrZero BigDecimal receivedQty,
		@NotNull @PositiveOrZero BigDecimal rejectedQty,
		RejectReason rejectReason,
		LocalDate expiryDate,
		LocalDate receivedDate) {
}
