package org.iskcon.kms.purchaseorder;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** Edit a DRAFT purchase order (E5-S3). Lines are replaced wholesale. */
public record UpdatePurchaseOrderRequest(
		LocalDate neededBy,
		@Size(max = 300, message = "That location is too long.") String deliveryLocation,
		@Size(max = 1000, message = "That note is too long.") String notes,
		@NotEmpty(message = "An order needs at least one line.") @Valid List<PoLineInput> lines) {
}
