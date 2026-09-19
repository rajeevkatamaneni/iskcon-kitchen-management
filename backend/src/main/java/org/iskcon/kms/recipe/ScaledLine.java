package org.iskcon.kms.recipe;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One scaled ingredient line: the ingredient's identity, how it is prepared, plus its raw and display
 * quantities.
 *
 * <p>{@code preparationNote} travels with the line and never with the ingredient (R-DUP-1): stock,
 * costing and sufficiency read this record by {@code ingredientId} and add up across lines, so two
 * lines of Cashew — one halved, one whole — are one ingredient to them, which is the point.
 */
public record ScaledLine(
		UUID ingredientId,
		String ingredientName,
		String preparationNote,
		BigDecimal rawQuantity,
		String rawUnit,
		BigDecimal displayQuantity,
		String displayUnit) {
}
