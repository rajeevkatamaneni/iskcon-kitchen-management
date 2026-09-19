package org.iskcon.kms.invoice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One item on a bill as the invoice form sends it (R-INV-3, R-INV-4). Three shapes, the three
 * {@code vendor_invoice_lines} has:
 *
 * <ul>
 *   <li><strong>a delivered item</strong>: {@code goodsReceiptLineId} (and its ingredient), pulled from
 *       a delivery being billed and locked — the person types only the billed quantity and the amount;</li>
 *   <li><strong>a direct item</strong>: {@code ingredientId} alone, on a bill with no delivery;</li>
 *   <li><strong>a one-off</strong>: {@code description} alone ("Plastic stool"), payable but never stock.</li>
 * </ul>
 *
 * <p>{@code billedQty} is always in {@code unit}, a real unit. A line billed in packs also sends
 * {@code packSizeId} and {@code packCount} ("4 × Bag (25 Kg)"), and the service checks that the two
 * agree: 4 × 25 Kg must be the 100 Kg sent as {@code billedQty}. The rate is never sent — it is
 * Amount ÷ Billed qty, derived by the database — so a client cannot state a price the figures do not
 * support.
 *
 * <p>Zero is allowed for both figures: "an item not billed stays at 0" (R-INV-4), and goods given free
 * are billed at nothing. Negative is not.
 */
public record InvoiceLineInput(
		UUID goodsReceiptLineId,
		UUID ingredientId,
		@Size(max = 200, message = "That description is too long.") String description,
		@NotNull(message = "Enter the quantity billed.")
		@PositiveOrZero(message = "The quantity billed can't be less than zero.")
		BigDecimal billedQty,
		@NotBlank(message = "Choose the unit the quantity is in.") String unit,
		UUID packSizeId,
		@Positive(message = "The number of packs has to be more than zero.") BigDecimal packCount,
		@NotNull(message = "Enter the amount for this item.")
		@PositiveOrZero(message = "An amount can't be less than zero.")
		BigDecimal amount) {
}
