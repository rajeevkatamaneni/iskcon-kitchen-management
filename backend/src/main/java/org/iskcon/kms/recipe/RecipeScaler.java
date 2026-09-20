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
 * <p>Two values come out of each line. The <strong>raw</strong> quantity is in the line's own unit
 * and is what downstream consumers (sufficiency in E4, orders in E5) compute against. The
 * <strong>display</strong> quantity is rounded and unit-promoted for a human — 24,000 gm shown as
 * 24 Kg — without the raw value ever losing precision.
 *
 * <p><strong>For a counted unit the two are one number, and that number is whole (T-425).</strong> A
 * mass or a volume divides and the split above is right for it: 2.4 Kg of rice is a real quantity of
 * rice and 0.4 of a kilo is 400 gm. A piece does not divide. So a counted line is rounded <em>up</em>
 * to a whole thing here, once, at the moment the figure is produced, and both fields carry it — see
 * the comment in {@link #scale}. Everything downstream reads {@code rawQuantity}, so that is the only
 * way the printed job card, the stock draw and the cost estimate can be made to agree; it is also why
 * nothing else in the application needed changing for the rule to hold everywhere.
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
	 * Scales one line. The raw quantity stays in {@code unit}; the display quantity is promoted
	 * within its metric family (gm↔Kg, ml↔L) so the number a cook reads is sensible, and rounded to
	 * two decimal places. A counted line is whole and rounded up in both fields, for the reasons
	 * written out in the branch below.
	 */
	public static ScaledQuantity scale(BigDecimal quantity, Unit unit, BigDecimal ratio) {
		BigDecimal raw = quantity.multiply(ratio, PRECISION);

		// A count is a whole thing measured in itself — three idlis is three idlis. It has no larger
		// or smaller sibling to be promoted into.
		//
		// **And the scaled figure is whole, rounded UP, and is the same number in both fields
		// (T-425).** This is the one place in the application that a counted requirement is
		// produced, and until now it produced fractions: the screen showed the rounded figure while
		// every consumer — the stock draw, the job card, sufficiency, the cost estimate — read
		// rawQuantity and used the fraction underneath it. Seeding staging left the stock screen
		// reading Banana 16.78 and Coconut 400.98 on hand, and nobody had typed either; a dish for
		// 140 people scaled from a recipe written for 200 drew 0.78 of a banana out of the store.
		// A real temple would see the same thing on its first day.
		//
		// CEILING, never HALF_UP. You cannot cook with 0.78 of a banana; you take a whole one, and
		// the remainder is the temple's business rather than the arithmetic's. Rounding to nearest
		// would have a recipe ask for nothing at all where it needs part of one thing — a scaled 0.4
		// coconut printed "0 pieces" on the recipe card, because DocumentGenerationService renders
		// this raw figure through Quantities.cooks, which rounds a count HALF_UP for display.
		// CEILING is also the choice BuyingAmount.stepped already makes for a counted line on the
		// shopping list, so what the temple is told to buy and what the kitchen is told to take now
		// round the same way.
		//
		// **Scale first, round once, at the end.** `raw` is already quantity x ratio, so a quarter
		// of a coconut a head across 800 heads is 200 coconuts and not 800. Rounding a per-head
		// share before the multiply is the mistake this ordering exists to prevent, and
		// RecipeScalerTest pins those exact numbers.
		//
		// **Rounded per line, not per ingredient and not per meal.** Every consumer reads
		// ScaledLine.rawQuantity and merges it under a key of its own — the job card by ingredient,
		// unit and preparation note; the stock draw and the cost basket by ingredient alone. Round
		// here and each of those sums is a sum of whole things, so the printed card, the ledger and
		// the money cannot disagree. Round after each consumer's merge instead and three merge keys
		// would give three different whole numbers for one dish, which is exactly the disagreement
		// this work exists to close. Measured before choosing: across the 458 ingredient lines in
		// the three recipe books this product ships, no recipe names the same ingredient on two
		// lines at all, so nothing the temple actually cooks pays for the choice.
		//
		// The word still agrees with the figure printed in front of it — "1 piece", "2 pieces"
		// (T-148) — and now there is only one figure for it to agree with.
		if (unit.family() == Unit.Family.COUNT) {
			BigDecimal whole = raw.setScale(0, RoundingMode.CEILING);
			return new ScaledQuantity(whole, unit.name(), whole, unit.label(whole));
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
