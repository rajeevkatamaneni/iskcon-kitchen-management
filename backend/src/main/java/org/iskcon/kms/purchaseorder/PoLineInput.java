package org.iskcon.kms.purchaseorder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.UUID;

/** A line on a manually created or edited PO (E5-S3). */
public record PoLineInput(
		@NotNull UUID ingredientId,
		@NotNull @Positive BigDecimal quantity,
		@NotBlank String unit,
		@PositiveOrZero BigDecimal expectedPrice) {
}
