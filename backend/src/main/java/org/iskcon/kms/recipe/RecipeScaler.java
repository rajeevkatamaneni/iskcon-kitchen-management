package org.iskcon.kms.recipe;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import org.iskcon.kms.ingredient.Unit;

/**
 * Linear recipe scaling (E2-S3), exactly as RM 2019's scaled-quantity column works: every ingredient
 * multiplied by the ratio of target yield to base yield. Non-linear culinary judgement (spice
 * curves) is deliberately out of scope; a recipe's notes carry that.
 *
 * <p>Two values come out of each line. The <strong>raw</strong> quantity is unrounded, in the line's
 * own unit, and is what downstream consumers (sufficiency in E4, orders in E5) compute against. The
 * <strong>display</strong> quantity is rounded and unit-promoted for a human — 24,000 gm shown as
 * 24 Kg — without the raw value ever losing precision.
 */
public final class RecipeScaler {

	/** Enough precision that a 50,000-serving scale neither overflows nor loses significant digits. */
	private static final MathContext PRECISION = MathContext.DECIMAL64;

	private RecipeScaler() {
	}

	/** target / base, at full working precision. Caller guarantees base &gt; 0. */
	public static BigDecimal ratio(BigDecimal baseYield, BigDecimal targetYield) {
		return targetYield.divide(baseYield, PRECISION);
	}

	/**
	 * Scales one line. The raw quantity stays in {@code unit}, unrounded; the display quantity is
	 * promoted within its metric family (gm↔Kg, ml↔L) so the number a cook reads is sensible, and
	 * rounded to two decimal places.
	 */
	public static ScaledQuantity scale(BigDecimal quantity, Unit unit, BigDecimal ratio) {
		BigDecimal raw = quantity.multiply(ratio, PRECISION);

		// Counts and servings are whole things measured in themselves — three idlis, a hundred
		// people fed. Neither has a larger or smaller sibling to be promoted into.
		if (unit.family() == Unit.Family.COUNT || unit.family() == Unit.Family.SERVINGS) {
			return new ScaledQuantity(raw, unit.name(), round(raw), unit.label());
		}

		// Convert to the family's base unit (grams or millilitres), then pick the unit that reads
		// best: the large unit once we're at 1000 of the small one, the small unit below that.
		BigDecimal inBase = raw.multiply(BigDecimal.valueOf(unit.baseFactor()), PRECISION);
		Unit displayUnit = pickDisplayUnit(unit.family(), inBase);
		BigDecimal displayValue = inBase.divide(BigDecimal.valueOf(displayUnit.baseFactor()), PRECISION);

		return new ScaledQuantity(raw, unit.name(), round(displayValue), displayUnit.label());
	}

	private static Unit pickDisplayUnit(Unit.Family family, BigDecimal inBase) {
		boolean atLeastOneLarge = inBase.abs().compareTo(BigDecimal.valueOf(1000)) >= 0;
		return switch (family) {
			case MASS -> atLeastOneLarge ? Unit.KG : Unit.GM;
			case VOLUME -> atLeastOneLarge ? Unit.L : Unit.ML;
			case COUNT -> Unit.PIECES;
			// Unreachable — scale() returns above for both — but the switch is exhaustive so that a
			// new family fails to compile here rather than falling through to a wrong unit.
			case SERVINGS -> Unit.SERVINGS;
		};
	}

	private static BigDecimal round(BigDecimal value) {
		return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
	}
}
