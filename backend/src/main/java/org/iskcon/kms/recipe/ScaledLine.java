package org.iskcon.kms.recipe;

import java.math.BigDecimal;
import java.util.UUID;

/** One scaled ingredient line: the ingredient's identity plus its raw and display quantities. */
public record ScaledLine(
		UUID ingredientId,
		String ingredientName,
		BigDecimal rawQuantity,
		String rawUnit,
		BigDecimal displayQuantity,
		String displayUnit,
		boolean sattvicProhibited) {
}
