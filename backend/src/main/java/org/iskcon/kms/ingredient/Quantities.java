package org.iskcon.kms.ingredient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;

/**
 * How a quantity is written where a person reads it (E11-S3).
 *
 * <p>The rule this holds already existed, in {@code RecipeScaler}, and was used by exactly one
 * feature. Everywhere else printed the stored value and the raw enum name — a job card asking for
 * {@code 2 KG} while the recipe card for the same line said {@code 2 Kg}, a low-stock email
 * announcing {@code Ghee (173542 ML)}. This is that rule, lifted out and given to everybody.
 *
 * <p><strong>There are two forms, and choosing between them is a question about the reader.</strong>
 *
 * <ul>
 *   <li>{@link #exact} — the ledger form. Somebody reconciles or is audited against this number, so
 *       it is not rounded. Stock balances, movement rows, batches, goods receipts, invoice lines.
 *       E3-S1 requires that stock shown equals the sum of its movements, and rounding each row
 *       independently would stop the rows adding up on the one screen whose job is that they do.
 *   <li>{@link #cooks} — the cook's form. Somebody weighs or buys against this number, so it is
 *       rounded the way a person rounds. Recipe lines, scaled recipes, planner targets, job cards,
 *       work orders, shopping lists, shortfalls.
 * </ul>
 *
 * <p>The mirror of this class in TypeScript is {@code frontend/lib/format.ts}. Two implementations
 * of one rule drift silently, so both are held to the same table of vectors — {@code QuantitiesTest}
 * here and {@code __tests__/quantities.test.ts} there, with identical inputs and identical expected
 * strings.
 *
 * <p><strong>Those tables are the whole mechanism, and they only cover the cases they list.</strong>
 * The two copies really did drift, over zero, on 2026-09-09, and both suites stayed green through it
 * — neither table had a zero vector, so there was nothing to notice that the screen said "0 L" while
 * the printed job card said "0 ml". A vector added to one table is not optional in the other: it is
 * the only thing standing between these two files and a silent disagreement.
 */
public final class Quantities {

	/** Indian digit grouping, matching the browser's {@code toLocaleString("en-IN")} exactly. */
	private static final Locale INDIA = Locale.forLanguageTag("en-IN");

	/** The larger and smaller unit of each convertible family. Counts and servings have neither. */
	private static final Map<Unit, Unit[]> FAMILY = Map.of(
			Unit.KG, new Unit[] {Unit.KG, Unit.GM},
			Unit.GM, new Unit[] {Unit.KG, Unit.GM},
			Unit.L, new Unit[] {Unit.L, Unit.ML},
			Unit.ML, new Unit[] {Unit.L, Unit.ML});

	private Quantities() {
	}

	/** The ledger form — the readable unit, the exact figure. */
	public static String exact(BigDecimal value, Unit unit) {
		return render(value, unit, false);
	}

	/** The cook's form — the readable unit, rounded the way a person rounds. */
	public static String cooks(BigDecimal value, Unit unit) {
		return render(value, unit, true);
	}

	/** The cook's form, for a unit that arrives as its stored name. */
	public static String cooks(BigDecimal value, String unit) {
		return cooks(value, parse(unit));
	}

	/** The ledger form, for a unit that arrives as its stored name. */
	public static String exact(BigDecimal value, String unit) {
		return exact(value, parse(unit));
	}

	private static Unit parse(String unit) {
		try {
			return unit == null ? null : Unit.valueOf(unit);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static String render(BigDecimal value, Unit unit, boolean forCooking) {
		// A quantity nobody has is not a zero — a dash says "no answer" where 0 would say
		// "none left", and a store room screen depends on the difference.
		if (value == null || unit == null) {
			return "—";
		}

		Unit[] family = FAMILY.get(unit);

		// Pieces and servings are whole things counted in themselves. Three idlis is three idlis,
		// and a hundred servings has no larger sibling to be promoted into.
		if (family == null) {
			BigDecimal shown = forCooking ? value.setScale(0, RoundingMode.HALF_UP) : value;
			return say(shown, unit, 3);
		}

		Unit large = family[0];
		Unit small = family[1];

		BigDecimal inBase = value.multiply(BigDecimal.valueOf(unit.baseFactor()));

		// Zero is said in the unit the thing is actually kept in, not in the family's small one.
		// The step-down rule exists to stop a fraction being printed — 0.6 Kg is 600 gm — and zero
		// has no fraction to step away from, so all the rule did was change the subject: a work
		// order's shortfall line reported "0 ml available" for an ingredient kept in litres, which
		// makes the reader convert before they can compare it with the litres asked for beside it.
		// The em dash above is a different case and is untouched — a null is "we have no figure", a
		// zero is "we have none of it", and the two must go on reading differently.
		//
		// This mirrors the identical decision in frontend/lib/format.ts, made 2026-09-09 after the
		// curd item's stock page read "0 ml" on hand against a reorder level of 15 L. The two copies
		// of this rule had disagreed about zero from that change until this one, and no test on
		// either side would have said so, because neither vector table held a zero at all — the
		// screen said "0 L" and the printed job card in the cook's hand said "0 ml", for the same
		// ingredient on the same day. Both tables carry the zero vectors now.
		//
		// signum() rather than equals(ZERO): a quantity arrives from JDBC scaled to its column, so a
		// genuine nothing is "0.000" and equals() would answer false on the scale alone.
		boolean empty = inBase.signum() == 0;
		Unit display = empty ? unit
				: inBase.abs().compareTo(BigDecimal.valueOf(1000)) >= 0 ? large : small;
		BigDecimal shown = inBase.divide(BigDecimal.valueOf(display.baseFactor()), 6, RoundingMode.HALF_UP);

		if (forCooking) {
			shown = roundAsAPersonWould(shown);

			// Rounding can carry a figure up over the line it was just measured against: 999.6 gm
			// rounds to 1000 gm, which is a kilo and should say so.
			if (display == small && shown.abs().compareTo(BigDecimal.valueOf(1000)) >= 0) {
				display = large;
				shown = shown.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
			}
		}

		return say(shown, display, forCooking ? 2 : 3);
	}

	/**
	 * A quantity rounded the way a person rounds it — to a step that grows with the size of the
	 * number.
	 *
	 * <p>Nobody weighs 134.4 gm of cardamom; they weigh 135. Nobody measures 10.08 Kg of rice; they
	 * measure 10. But 4.7 gm of camphor is not 5 — at that size half a gram is the honest step. So
	 * the step is not fixed, it climbs: tenths below one, halves to ten, ones to a hundred, fives to
	 * a thousand, tens above.
	 *
	 * <p>The error is bounded by the step and cannot compound, because this runs once, last, on a
	 * value that has already been through every calculation it is going to. Round then compute and
	 * the errors stack; compute then round and they cannot.
	 */
	private static BigDecimal roundAsAPersonWould(BigDecimal value) {
		BigDecimal size = value.abs();
		BigDecimal step =
				size.compareTo(BigDecimal.ONE) < 0 ? new BigDecimal("0.1")
				: size.compareTo(BigDecimal.TEN) < 0 ? new BigDecimal("0.5")
				: size.compareTo(BigDecimal.valueOf(100)) < 0 ? BigDecimal.ONE
				: size.compareTo(BigDecimal.valueOf(1000)) < 0 ? BigDecimal.valueOf(5)
				: BigDecimal.TEN;

		return value.divide(step, 0, RoundingMode.HALF_UP).multiply(step);
	}

	private static String say(BigDecimal value, Unit unit, int maxDecimals) {
		NumberFormat format = NumberFormat.getInstance(INDIA);
		format.setMaximumFractionDigits(maxDecimals);
		format.setGroupingUsed(true);
		return format.format(value) + " " + unit.label();
	}
}
