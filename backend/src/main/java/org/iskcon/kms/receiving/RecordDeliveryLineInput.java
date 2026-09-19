package org.iskcon.kms.receiving;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One line of "Record a delivery" (R-DEL-3), in the order line's own {@code unit}: the screen turns
 * "4 bags" into 100 Kg before it sends. The same four facts a {@link ReceiptLineInput} carries, and
 * they become one: the Deliveries screen records through {@code ReceivingService} and nothing else.
 *
 * <p><strong>No price</strong> (R-DEL-5): a price belongs to the invoice (R-VEN-4). No received date
 * either — the goods came today, the temple's today, which is what {@code ReceivingService} writes.
 */
public record RecordDeliveryLineInput(
		@NotNull(message = "Say which item this is.") UUID poLineId,
		@NotNull(message = "Enter how much arrived in good condition.")
		@PositiveOrZero(message = "An amount received cannot be less than nothing.")
		BigDecimal receivedQty,
		@NotNull(message = "Enter how much was sent back, or zero.")
		@PositiveOrZero(message = "An amount rejected cannot be less than nothing.")
		BigDecimal rejectedQty,
		RejectReason rejectReason,
		LocalDate expiryDate) {
}
