package org.iskcon.kms.shoppinglist;

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
 */
public record UpdateShoppingListLineRequest(
		@Positive(message = "Enter an amount greater than zero.") BigDecimal suggestedQty,
		boolean included) {
}
