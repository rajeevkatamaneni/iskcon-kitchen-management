package org.iskcon.kms.ingredientrequest;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of a request as it is read back: what was asked for, and — once the store has answered
 * the counter — what actually went over it.
 *
 * <p>{@code issuedQuantity} is null until the issue is recorded, and may then be zero: the store
 * handed over nothing for that line. Zero and null are different facts and both are worth keeping,
 * which is why the field is not defaulted.
 */
public record IngredientRequestLineView(
		UUID id,
		int lineNo,
		UUID ingredientId,
		String ingredientName,
		BigDecimal quantity,
		String unit,
		BigDecimal issuedQuantity,
		String issuedUnit,
		String note) {
}
