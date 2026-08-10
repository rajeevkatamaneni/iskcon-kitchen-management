package org.iskcon.kms.purchaseorder;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** Edit a DRAFT purchase order (E5-S3). Lines are replaced wholesale. */
public record UpdatePurchaseOrderRequest(
		LocalDate neededBy,
		@Size(max = 300) String deliveryLocation,
		@Size(max = 1000) String notes,
		@NotEmpty @Valid List<PoLineInput> lines) {
}
