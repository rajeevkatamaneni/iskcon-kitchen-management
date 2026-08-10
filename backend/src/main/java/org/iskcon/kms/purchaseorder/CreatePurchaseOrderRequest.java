package org.iskcon.kms.purchaseorder;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Create a purchase order manually (E5-S3). */
public record CreatePurchaseOrderRequest(
		@NotNull UUID vendorId,
		LocalDate neededBy,
		@Size(max = 300) String deliveryLocation,
		@Size(max = 1000) String notes,
		@NotEmpty @Valid List<PoLineInput> lines) {
}
