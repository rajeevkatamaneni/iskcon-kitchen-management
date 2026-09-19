package org.iskcon.kms.shoppinglist;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One kind of pack in a shopping-list line's buying amount (R-SL-2, R-SL-3; T-259): "4 × Bag (25 Kg)"
 * is {@code count} 4 of a pack labelled "Bag (25 Kg)".
 *
 * @param packSizeId the {@code ingredient_pack_sizes} row, so the screen and the purchase order can
 *     name the same pack rather than re-deriving it from a label
 * @param label how the app writes the pack: "Bag (25 Kg)" for a named pack, "500 gm" for a plain one
 * @param perPackQty the pack's size in the line's own {@code unit} — 25 on a line kept in Kg, 25000
 *     on one kept in gm — so the line's {@code suggestedQty} is exactly the sum of count × this
 * @param count how many of this pack
 */
public record BuyPackView(UUID packSizeId, String label, BigDecimal perPackQty, int count) {
}
