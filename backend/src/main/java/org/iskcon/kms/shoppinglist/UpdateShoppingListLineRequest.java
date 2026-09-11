package org.iskcon.kms.shoppinglist;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * A human decision about a shopping-list line (E5-S2): how much to buy, and whether to buy it.
 *
 * <p>This is the only thing the shopping list stores. Everything else on the screen — the quantity
 * it suggests, the vendor, the dates, why the line is there at all — is computed on every read from
 * the meal plan, the store room and the live purchase orders, and is never written down (T-132).
 *
 * <p><strong>There is no vendor field, and its removal closed a trap rather than a feature.</strong>
 * {@code shopping_list_lines.suggested_vendor_id} was a column until V121 and pure derivation for the
 * whole of its life: both writers set it from the ingredient's preferred vendor, and no screen ever
 * sent one — the request accepted a value nothing produced. The snapshot D-25 asks for already exists
 * where it belongs, on {@code purchase_orders.vendor_id}, written when the order is created and never
 * derived again.
 *
 * <p><strong>{@code included} is required, and the boxed type is what makes that possible.</strong>
 * It was a primitive {@code boolean}, which was recorded as safe on the grounds that the column is
 * {@code NOT NULL} and an omission would therefore fail loudly. It would not have: Jackson gives a
 * missing primitive its Java default, so a body that simply left {@code included} out arrived here
 * as {@code false} and the upsert below wrote that over whatever the line already said — silently
 * unticking something a person had chosen to buy. The column never saw a null and so never had the
 * chance to refuse. That is the same shape as the {@code suggested_vendor_id} defect that opened
 * this batch: a value nobody sent, written as though they had.
 *
 * <p>No screen can reach it — {@code updateShoppingListLine} in {@code lib/api.ts} types
 * {@code included} as a required boolean, so the only client always sends it — which is why this is
 * a latch rather than a bug fix. Boxed and {@code @NotNull}, an omission is now KMS-400001 naming
 * the field, and no request that succeeds today behaves any differently.
 */
public record UpdateShoppingListLineRequest(
		@Positive(message = "Enter an amount greater than zero.") BigDecimal suggestedQty,
		@NotNull(message = "Say whether this line is being bought.") Boolean included) {
}
