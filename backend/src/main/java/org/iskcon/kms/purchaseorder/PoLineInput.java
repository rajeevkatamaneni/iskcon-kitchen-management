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
 *
 * <p><strong>A catalogue line may be ordered in a pack</strong> (R-SL-3, T-260): {@code packSizeId}
 * names one of the ingredient's own pack sizes and {@code packCount} says how many — the "4 × Bag
 * (25 Kg)" the sheet prints. Both or neither. When they are sent, <em>the pack decides the amount</em>:
 * the server stores {@code quantity = packCount × the pack's size}, in the pack's unit, and ignores
 * the {@code quantity} sent beside it, so the stock-unit amount (100 Kg) that receiving and costing
 * read can never disagree with the packs the vendor was asked for. {@code quantity} stays required all
 * the same, because every line must still say an amount and a client that knows nothing about packs
 * sends exactly what it sent before. {@code unit} keeps one job on a pack line: it is the unit
 * {@code expectedPrice} is a price per, and the server converts that price into the pack's unit when
 * the two differ (₹0.0712 per gm on a line stored in Kg is ₹71.20 per Kg). See
 * {@code PurchaseOrderService.resolvePack}.
 */
public record PoLineInput(
		UUID ingredientId,
		@Size(max = 200, message = "That description is too long.") String description,
		@NotNull(message = "Enter how much to order.")
		@Positive(message = "Enter an amount greater than zero.")
		BigDecimal quantity,
		@NotBlank(message = "Choose a unit.") String unit,
		@PositiveOrZero(message = "A price cannot be less than nothing.") BigDecimal expectedPrice,
		UUID packSizeId,
		@Positive(message = "Order at least one pack.") BigDecimal packCount) {
}
