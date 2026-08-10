package org.iskcon.kms.recipe;

import java.math.BigDecimal;

/**
 * A scaled quantity in two forms: the unrounded {@code rawQuantity} in its original {@code rawUnit}
 * (for downstream calculation) and a rounded, unit-promoted {@code displayQuantity} /
 * {@code displayUnit} (for a human).
 */
public record ScaledQuantity(
		BigDecimal rawQuantity,
		String rawUnit,
		BigDecimal displayQuantity,
		String displayUnit) {
}
