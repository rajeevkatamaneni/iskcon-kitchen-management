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
 *
 * <p><strong>No price</strong> (R-DEL-5, T-261). This record used to carry {@code unitPrice}, what
 * was paid, which the service stored on the line and wrote back as the vendor's list price. A price
 * now belongs to the invoice (R-VEN-4). A client that still sends {@code unitPrice} is not refused —
 * Spring Boot leaves Jackson's {@code FAIL_ON_UNKNOWN_PROPERTIES} off — but the figure goes nowhere.
 */
public record ReceiptLineInput(
		@NotNull(message = "Say which line of the order this is.") UUID poLineId,
		@NotNull(message = "Enter how much arrived in good condition.")
		@PositiveOrZero(message = "An amount received cannot be less than nothing.")
		BigDecimal receivedQty,
		@NotNull(message = "Enter how much was sent back, or zero.")
		@PositiveOrZero(message = "An amount rejected cannot be less than nothing.")
		BigDecimal rejectedQty,
		RejectReason rejectReason,
		LocalDate expiryDate,
		LocalDate receivedDate) {
}
