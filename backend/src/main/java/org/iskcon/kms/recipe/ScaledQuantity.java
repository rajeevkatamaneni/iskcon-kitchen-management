package org.iskcon.kms.recipe;

import java.math.BigDecimal;

/**
 * A scaled quantity in two forms: {@code rawQuantity} in its original {@code rawUnit} (for
 * downstream calculation) and a rounded, unit-promoted {@code displayQuantity} /
 * {@code displayUnit} (for a human).
 *
 * <p><strong>For a unit in {@link org.iskcon.kms.ingredient.Unit.Family#COUNT} the two quantities
 * are the same whole number, by construction (T-425).</strong> The fields are not collapsed into one
 * — a mass or a volume genuinely needs both, and this record carries every family — but for a count
 * they can never disagree, because {@link RecipeScaler#scale} rounds the count up once and puts the
 * result in both. That is the point of the change: the screen used to show the rounded figure while
 * the stock draw, the job card and the cost estimate all read the fraction underneath it, so the
 * money and the quantity told different stories about the same coconut.
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
