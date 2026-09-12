package org.iskcon.kms.recipe;

import java.math.BigDecimal;

/**
 * A scaled quantity in two forms: the unrounded {@code rawQuantity} in its original {@code rawUnit}
 * (for downstream calculation) and a rounded, unit-promoted {@code displayQuantity} /
 * {@code displayUnit} (for a human).
 *
 * <p>{@code displayUnit} is the word that agrees with {@code displayQuantity} — "piece" when the
 * rounded figure is exactly one, "pieces" otherwise — because the recipe page prints the two fields
 * side by side as one phrase with nothing in between to fix the grammar. It is therefore a word to
 * show, not a unit to compute with: anything that needs the unit itself reads {@code rawUnit}, which
 * is the stored enum name.
 */
public record ScaledQuantity(
		BigDecimal rawQuantity,
		String rawUnit,
		BigDecimal displayQuantity,
		String displayUnit) {
}
