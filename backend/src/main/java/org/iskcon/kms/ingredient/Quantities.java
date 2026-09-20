package org.iskcon.kms.ingredient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.iskcon.kms.document.IndianNumbers;

/**
 * How a quantity is written where a person reads it (E11-S3).
 *
 * <p>The rule this holds already existed, in {@code RecipeScaler}, and was used by exactly one
 * feature. Everywhere else printed the stored value and the raw enum name — a job card asking for
 * {@code 2 KG} while the recipe card for the same line said {@code 2 Kg}, a low-stock email
 * announcing {@code Ghee (173542 ML)}. This is that rule, lifted out and given to everybody.
 *
 * <p><strong>The original was left in place when that lift happened</strong> (2026-08-10 for
 * {@code RecipeScaler}, 2026-08-30 for this), so there were three copies of one rule and not two.
 * {@code RecipeScaler} went on choosing its own display unit with its own {@code >= 1000} test, and
 * when the zero case reached here the recipe scale preview would still have said "0 ml" for an
 * ingredient kept in litres. It calls {@link #displayUnit} now, so that copy is gone. What is left is
 * this and {@code frontend/lib/format.ts} — two implementations, in two languages, that cannot be
 * merged, which is what the vector tables below are for.
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
 * <p><strong>Each form has a second entry point for a set of figures read together</strong> —
 * {@link #oneUnitFor} and {@link #cooksOneUnitFor} (T-364). {@link #exact} and {@link #cooks} decide
 * on one number knowing nothing about the numbers beside it, which is right for a figure standing on
 * its own and wrong for a work order line that says "800 gm / 12 Kg". Where two figures of one family
 * are read in a single act — a shortfall pair, a row's lots against its total, a scaled yield beside
 * its base — the set is what chooses the unit, and these are how to ask for that.
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

	/**
	 * The larger and smaller unit of each convertible family. Counts and servings have neither, and a
	 * unit missing from this map is shown in itself — which is right for a count and would be merely
	 * unpromoted, never wrong, for anything else. This is the only place the backend declares that
	 * pairing now: {@code RecipeScaler} used to declare it a second time, in a {@code switch} on
	 * {@link Unit.Family}.
	 */
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

	/**
	 * Several figures about one thing, all said in <strong>one</strong> unit — the set's unit rather
	 * than each figure's own (T-364). The ledger form; {@link #cooksOneUnitFor} is the other one.
	 *
	 * <p>This is the twin of {@code oneUnitFor} in {@code frontend/lib/format.ts}, added in the same
	 * week for the same defect on the screens: Rajeev, reviewing the inventory list on staging, found
	 * one row reading <em>2.06 Kg on hand, 2.04 Kg committed, 20 gm available</em>. Nothing in it was
	 * wrong. {@link #exact} promotes from 1,000 up, it is asked separately for each figure, and 0.02
	 * Kg genuinely is 20 gm. That is exactly the defect — the choice is made per call, on one number,
	 * knowing nothing about the numbers beside it, so a row meant to be read as a subtraction changes
	 * scale in the middle of itself and stops visibly adding up. The printed documents had the same
	 * fault in their own places: a work order line short of 0.8 Kg out of 12 printed
	 * <em>"800 gm / 12 Kg"</em>, and a recipe card scaled from half a litre printed
	 * <em>"Scaled to 2 L (base 500 ml)"</em>.
	 *
	 * <p><strong>This does not change {@link #exact} or {@link #cooks}, deliberately.</strong> Those
	 * have callers all over the application and their behaviour is pinned by the vector table in
	 * {@code QuantitiesTest} and its twin in {@code __tests__/quantities.test.ts}; a figure standing
	 * on its own should still be said the way a person would say it. What was missing is a way to say
	 * a <em>set</em> of them together, so this is a second entry point rather than a new rule for the
	 * old one.
	 *
	 * <p><strong>Which unit wins: the biggest figure's.</strong> Reading 1.96, 2.04 and -0.08 Kg is
	 * reading one scale; reading 1,960, 2,040 and -80 gm is the same numbers with three noughts on
	 * each. The largest figure is the one that says how big the quantities in this set are, so it is
	 * the one that chooses. A set whose figures are all zero keeps the unit the thing is held in, as
	 * a lone zero already does.
	 *
	 * <p><strong>Nothing is rounded away.</strong> This is the ledger form, where the figures have to
	 * go on adding up: three decimals is a gram of a kilo, and 418.2 gm restated in kilograms is
	 * 0.4182, which three would quietly turn into 0.418. So the decimals are however many the set
	 * needs to say every figure in it exactly, up to six — six because a ledger quantity is stored to
	 * three decimal places in its own unit and a conversion can push that three further. It fixes the
	 * other end of the same problem at the same time: 0.4 gm in kilograms is 0.0004, and "0 Kg" would
	 * say the shelf is empty when it is not.
	 *
	 * <p>Pieces, and any unit outside the convertible families, have nothing to convert into: what
	 * comes back is {@link #exact} itself.
	 *
	 * <p>The backend has no caller of this ledger form today — every printed document is the cook's
	 * form below. It is here because the TypeScript table of vectors is the only thing standing
	 * between these two files and a silent disagreement, and that table is written against the ledger
	 * form, whose unit <em>is</em> the chosen unit because it rounds nothing. {@code QuantitiesTest}
	 * runs it against this method, line for line.
	 *
	 * @param unit the unit every figure is stored in — one ingredient's canonical unit
	 * @param figures every figure that will be printed in the set, nulls included: what is
	 *     <em>shown</em> decides the unit, so leaving one out can change the answer
	 * @return a renderer for one figure, to be used for every figure in that set
	 */
	public static Function<BigDecimal, String> oneUnitFor(Unit unit, List<BigDecimal> figures) {
		return oneUnitFor(unit, figures, false);
	}

	/**
	 * The cook's form of {@link #oneUnitFor} — one unit for the whole set, each figure rounded the
	 * way a person rounds it. This is what the job card, the work order and the recipe card use.
	 *
	 * <p><strong>Where it differs from the TypeScript twin, and why a printed page needs the
	 * difference.</strong> {@code format.ts} has only the ledger form, because the screens that
	 * needed it are ledger screens — a stock row is a subtraction somebody reconciles. A printed
	 * document is weighed against instead, so its figures go through
	 * {@link #roundAsAPersonWould} exactly as {@link #cooks} already rounds them. The unit choice is
	 * the same rule, from the same biggest figure; only the rounding is added.
	 *
	 * <p><strong>Each figure is rounded at its own scale, then restated in the set's unit.</strong>
	 * This is the one thing that is not simply "cooks() with a fixed unit", and it is what stops the
	 * fix making the page worse. A work order line for 12 Kg of rice drawn from a lot of 11.992 Kg
	 * and a lot of 8 gm is said in kilograms, because 12 is what says how big the row is. Rounding
	 * the 8 gm figure <em>as kilograms</em> would round it to a tenth of a kilo and print "0 Kg" —
	 * a lot the storekeeper is being sent to, reported as nothing. Rounded as the 8 grams it is and
	 * then written in the row's unit, it prints "0.008 Kg", which adds up with the line above it and
	 * is still true. So the set decides the unit and the figure decides its own precision.
	 *
	 * <p>The second promotion {@link #cooks} does — 999.6 gm rounds to 1,000 gm, which is a kilo and
	 * says so — needs nothing here. It happens in the family's base unit, and moving a figure between
	 * gm and Kg does not change how much of it there is; the set's unit is chosen once and stands.
	 */
	public static Function<BigDecimal, String> cooksOneUnitFor(Unit unit, List<BigDecimal> figures) {
		return oneUnitFor(unit, figures, true);
	}

	/** The cook's form of {@link #oneUnitFor}, for a unit that arrives as its stored name. */
	public static Function<BigDecimal, String> cooksOneUnitFor(String unit, List<BigDecimal> figures) {
		return cooksOneUnitFor(parse(unit), figures);
	}

	private static Function<BigDecimal, String> oneUnitFor(
			Unit unit, List<BigDecimal> figures, boolean forCooking) {

		Unit[] family = unit == null ? null : FAMILY.get(unit);

		// A count has no sibling to be moved into, so there is no set-wide choice to make and every
		// figure is said exactly as it would be on its own. The same answer as format.ts gives.
		if (family == null) {
			return value -> render(value, unit, forCooking);
		}

		BigDecimal factor = BigDecimal.valueOf(unit.baseFactor());

		// The biggest figure chooses, and a set of nothing keeps the unit the thing is kept in —
		// displayUnit() already answers that for a zero, so it is asked rather than second-guessed.
		BigDecimal biggest = BigDecimal.ZERO;
		for (BigDecimal figure : figures) {
			if (figure != null) {
				BigDecimal base = figure.abs().multiply(factor);
				if (base.compareTo(biggest) > 0) {
					biggest = base;
				}
			}
		}
		Unit display = displayUnit(unit, biggest);
		BigDecimal displayFactor = BigDecimal.valueOf(display.baseFactor());

		// However many places the set needs to say each of its figures exactly, never fewer than the
		// single-figure form gives and never more than six. Asked of the figure as it will be shown,
		// after any rounding, so the cook's form is not given decimals it has already thrown away.
		int decimals = forCooking ? 2 : 3;
		for (BigDecimal figure : figures) {
			if (figure == null) {
				continue;
			}
			int needed = shown(figure, unit, factor, displayFactor, forCooking)
					.stripTrailingZeros().scale();
			decimals = Math.max(decimals, Math.min(6, Math.max(0, needed)));
		}

		int maxDecimals = decimals;
		return value -> value == null
				? "—"
				: say(shown(value, unit, factor, displayFactor, forCooking), display, maxDecimals);
	}

	/** One figure of a set, in the set's chosen unit, rounded for whoever is going to read it. */
	private static BigDecimal shown(
			BigDecimal value, Unit unit, BigDecimal factor, BigDecimal displayFactor,
			boolean forCooking) {

		BigDecimal base = value.multiply(factor);

		if (forCooking) {
			// Rounded at the figure's own scale — see the note on cooksOneUnitFor about the 8 gm lot
			// in a row said in kilograms. displayUnit() is what "its own scale" means everywhere else
			// in this file, so it is what it means here.
			Unit own = displayUnit(unit, base);
			BigDecimal ownFactor = BigDecimal.valueOf(own.baseFactor());
			base = roundAsAPersonWould(base.divide(ownFactor, 6, RoundingMode.HALF_UP))
					.multiply(ownFactor);
		}

		return base.divide(displayFactor, 6, RoundingMode.HALF_UP);
	}

	/**
	 * Which unit a figure is <em>said</em> in: the family's large unit once there is a whole one of
	 * them, the small one below that, and — when there is none of it at all — the unit the thing is
	 * actually kept in.
	 *
	 * <p>Public because one other renderer legitimately needs this half of the rule on its own.
	 * {@code RecipeScaler} hands a scaled recipe line to the screen as a number and a unit in separate
	 * fields rather than as a finished string, so it cannot call {@link #cooks} or {@link #exact} —
	 * and so, for a year, it kept its own copy of this choice instead. That copy is gone.
	 *
	 * @param unit the unit the quantity is stored and measured in
	 * @param inBase that same quantity converted into the family's base unit — grams or millilitres.
	 *     Asked for rather than computed here because the caller already holds it (it has to divide by
	 *     the chosen unit's factor next), and computing it twice with two different roundings is
	 *     exactly the kind of near-agreement that hides.
	 */
	public static Unit displayUnit(Unit unit, BigDecimal inBase) {
		Unit[] family = FAMILY.get(unit);

		// Pieces and servings are whole things counted in themselves, with no sibling to move into.
		if (family == null) {
			return unit;
		}

		// Zero is said in the unit the thing is actually kept in, not in the family's small one.
		// The step-down rule exists to stop a fraction being printed — 0.6 Kg is 600 gm — and zero
		// has no fraction to step away from, so all the rule did was change the subject: a work
		// order's shortfall line reported "0 ml available" for an ingredient kept in litres, which
		// makes the reader convert before they can compare it with the litres asked for beside it.
		// The em dash in render() is a different case and is untouched — a null is "we have no
		// figure", a zero is "we have none of it", and the two must go on reading differently.
		//
		// This mirrors the identical decision in frontend/lib/format.ts, made 2026-09-09 after the
		// curd item's stock page read "0 ml" on hand against a reorder level of 15 L. The copies of
		// this rule had disagreed about zero from that change until 2026-09-10, and no test anywhere
		// would have said so, because no vector table held a zero at all — the screen said "0 L" and
		// the printed job card in the cook's hand said "0 ml", for the same ingredient on the same day.
		//
		// signum() rather than equals(ZERO): a quantity arrives from JDBC scaled to its column, so a
		// genuine nothing is "0.000" and equals() would answer false on the scale alone.
		if (inBase.signum() == 0) {
			return unit;
		}

		return inBase.abs().compareTo(BigDecimal.valueOf(1000)) >= 0 ? family[0] : family[1];
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

		Unit display = displayUnit(unit, inBase);
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

	/**
	 * The figure and its word, as one phrase.
	 *
	 * <p>{@code value} here is the figure as it will be <em>shown</em> — already rounded by
	 * {@link #roundAsAPersonWould} where the cook's form asked for it, and already promoted between
	 * gm and Kg. That is why the word is chosen here and not by the caller: this is the last place
	 * that holds the number the reader will actually see. A job card asking for 1.2 stools prints
	 * "1 piece" because the cook's form made it a whole thing before it got here, and a ledger row
	 * prints "1.2 pieces" because it did not, and both are right.
	 *
	 * <p>It goes through {@link Unit#label(BigDecimal)} rather than {@link Unit#label()} because a
	 * label that has not been shown its number cannot agree with it — the "1 pieces" defect (T-144).
	 * Reading {@code label()} directly anywhere a quantity is in scope brings it straight back, and
	 * {@code UnitLabelAgreementTest} fails the build if anyone does.
	 */
	private static String say(BigDecimal value, Unit unit, int maxDecimals) {
		// Indian grouping, matching the browser's toLocaleString("en-IN"). This used to say "exactly"
		// over a JDK NumberFormat for en-IN, which it was not: that formatter groups in threes, so
		// 1,50,000 pieces printed as "150,000 pieces" on a job card while the screen said
		// "1,50,000 pieces" (T-279). Below a lakh the two agree, which is why no vector caught it.
		return IndianNumbers.group(value, 0, maxDecimals) + " " + unit.label(value);
	}
}
