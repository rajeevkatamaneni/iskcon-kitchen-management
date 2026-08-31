package org.iskcon.kms.inventory;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.ingredient.Unit;

/**
 * One ingredient's whole requirement, and the batches {@link FefoAllocator} would take it from.
 *
 * <p>Quantities are in the family's base unit — grams, millilitres, pieces — since that is what the
 * ledger sums in. {@code canonicalUnit} is carried so a caller can convert back to the unit a person
 * reads the ingredient in without a second lookup.
 *
 * <p>The draws are empty when nothing was needed, and they are <em>short</em> rather than absent
 * when the store cannot cover the line: a shortfall is reported beside them in
 * {@link StockAllocation}, and it is the caller's business what to do about it.
 */
public record AllocatedLine(
		UUID ingredientId,
		String ingredientName,
		Unit canonicalUnit,
		BigDecimal requiredBase,
		List<BatchDraw> draws) {
}
