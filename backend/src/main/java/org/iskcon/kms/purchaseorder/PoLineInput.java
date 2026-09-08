package org.iskcon.kms.purchaseorder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A line on a manually created or edited PO (E5-S3).
 *
 * <p><strong>Exactly one of {@code ingredientId} and {@code description} is sent</strong> (T-024).
 * A line either names something in the catalogue or describes something that is not in it — four
 * plastic stools from a furniture shop — and never both or neither. The database says the same
 * thing with {@code po_lines_has_exactly_one_subject}; the service says it in words somebody can
 * act on, as {@code KMS-400128}, before anything is written.
 *
 * <p>Neither is bean-validated here, deliberately. {@code @NotNull} on {@code ingredientId} was the
 * old rule and is what this task removes; putting {@code @NotNull} on either alone would be wrong,
 * and a class-level constraint would surface the refusal as a generic {@code VALIDATION_FAILED}
 * instead of the specific code with the next step on it. The exclusivity check therefore lives in
 * {@code PurchaseOrderService.insertLines}, alongside the unit-family check that is decided about
 * the whole order for the same reason.
 */
public record PoLineInput(
		UUID ingredientId,
		@Size(max = 200) String description,
		@NotNull @Positive BigDecimal quantity,
		@NotBlank String unit,
		@PositiveOrZero BigDecimal expectedPrice) {
}
