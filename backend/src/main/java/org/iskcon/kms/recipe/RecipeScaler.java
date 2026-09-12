package org.iskcon.kms.recipe;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import org.iskcon.kms.ingredient.Quantities;
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
 *
 * <p><strong>Which unit that display quantity is said in is not decided here.</strong> It was, once:
 * this class is where the rule was written, {@link Quantities} was lifted out of it on 2026-08-30 to
 * give the rest of the application the same answer, and the copy here was left in place. The two then
 * drifted, exactly as two copies do — {@code Quantities} learned on 2026-09-10 that a quantity of
 * nothing is said in the unit the thing is kept in ("0 L" for an ingredient measured in litres, not
 * "0 ml"), and this file did not, so the recipe scale preview was the last screen in the application
 * still saying it the old way. It calls {@link Quantities#displayUnit} now.
 *
 * <p>The <em>rounding</em> is still this class's own, and deliberately: a scaled line is rounded to
 * two decimal places because it is a number the screen puts in a column beside the raw one, where
 * {@code Quantities.cooks} rounds to a step a person can weigh to (135 gm, 10 Kg). Those are two
 * different jobs and merging them would change every figure on the scale preview. Only the unit
 * choice was ever the same rule.
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

		// A count is a whole thing measured in itself — three idlis is three idlis. It has no larger
		// or smaller sibling to be promoted into.
		//
		// The word is chosen from the ROUNDED figure, because that is the figure the recipe page
		// prints in front of it (app/recipes/[id]/page.tsx renders displayQuantity and displayUnit
		// side by side with no formatter between them). A line that scales to 0.999 pieces is shown
		// "1", so it must say "1 piece"; choosing from the raw 0.999 would say "1 pieces", which is
		// the defect this line used to have for every count of exactly one (T-148). Rounding once
		// and handing the same value to both fields keeps the two from ever disagreeing.
		if (unit.family() == Unit.Family.COUNT) {
			BigDecimal shown = round(raw);
			return new ScaledQuantity(raw, unit.name(), shown, unit.label(shown));
		}

		// Convert to the family's base unit (grams or millilitres), then ask the one place that knows
		// which unit reads best at that size — the large one once there are 1000 of the small, the
		// small one below that, and the line's own unit when there is nothing of it at all.
		BigDecimal inBase = raw.multiply(BigDecimal.valueOf(unit.baseFactor()), PRECISION);
		Unit displayUnit = Quantities.displayUnit(unit, inBase);
		BigDecimal displayValue = inBase.divide(BigDecimal.valueOf(displayUnit.baseFactor()), PRECISION);

		// Kg, gm, L and ml are one word at every count, so label(shown) and label() say the same thing
		// here today. It asks with the figure anyway: this is a number and a word printed as one
		// phrase, and the next counted-but-convertible unit anybody adds should not have to find
		// this line to be right.
		BigDecimal shown = round(displayValue);
		return new ScaledQuantity(raw, unit.name(), shown, displayUnit.label(shown));
	}

	private static BigDecimal round(BigDecimal value) {
		return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
	}
}
