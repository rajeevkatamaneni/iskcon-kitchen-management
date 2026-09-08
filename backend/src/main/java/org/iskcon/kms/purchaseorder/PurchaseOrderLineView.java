package org.iskcon.kms.purchaseorder;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A line on a purchase order. {@code expectedPrice} is optional — the temple may negotiate on
 * delivery.
 *
 * <p><strong>A line has exactly one subject</strong> (T-024). Either {@code ingredientId} and
 * {@code ingredientName} are set and {@code description} is null — an ordinary catalogue line — or
 * {@code ingredientId} and {@code ingredientName} are null and {@code description} carries what is
 * being bought: "Plastic stool". The database enforces the exclusivity
 * ({@code po_lines_has_exactly_one_subject}), so {@code ingredientId() == null} is a reliable
 * discriminator and every consumer branches on that one question.
 *
 * <p><strong>{@code ingredientName} is nullable, and callers must treat it as such.</strong> The
 * components are plain nullable fields rather than {@code Optional} — that is this codebase's
 * convention for a record that crosses the JSON boundary — which means the compiler will not remind
 * anybody. Two shapes of mistake have already been found by reading rather than by a failure:
 * {@code String.join}/{@code Collectors.joining} append a null as the four characters "null" with
 * no warning at all, and a glossary or map lookup on the name throws. Where a line's subject is
 * wanted for display, it is {@code ingredientName() != null ? ingredientName() : description()},
 * and never the name alone.
 *
 * <p>A described line is <em>orderable and payable but never receivable</em>:
 * {@code goods_receipt_lines.ingredient_id} and {@code stock_movements.ingredient_id} are both NOT
 * NULL and stay that way, and {@code ReceivingService} refuses a receipt against a described line
 * with {@code KMS-400129}.
 */
public record PurchaseOrderLineView(
		UUID id,
		UUID ingredientId,
		String ingredientName,
		String description,
		BigDecimal quantity,
		String unit,
		BigDecimal expectedPrice) {

	/**
	 * What this line is for, in words — the catalogue name, or the description when there is no
	 * catalogue entry. Never null: the exclusivity constraint guarantees one of the two is set.
	 *
	 * <p>Every display path should use this rather than {@link #ingredientName()}. It exists because
	 * the null that {@code ingredientName()} may return does not fail loudly anywhere it is used —
	 * it prints as "null" in a joined string and vanishes from an inner join.
	 *
	 * <p>{@code @JsonIgnore} deliberately. This is a convenience over the two real components and
	 * not a third field; the wire shape of a PO line is fixed by {@code frontend/lib/api.ts}, and a
	 * derived property appearing in the JSON that the client's type does not declare is exactly the
	 * kind of drift the required-and-nullable convention exists to prevent.
	 */
	@JsonIgnore
	public String subject() {
		return ingredientName != null ? ingredientName : description;
	}
}
