package org.iskcon.kms.ingredient;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.iskcon.kms.recipe.RecipeScaler;
import org.iskcon.kms.recipe.ScaledQuantity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The vector table for the one display rule (E11-S3).
 *
 * <p>This table is duplicated, deliberately and identically, in {@code frontend/__tests__/
 * quantities.test.ts}. The rule has to exist twice — the screens are TypeScript and the job card,
 * recipe card, purchase-order sheet and work order are rendered here — and two implementations of
 * one rule drift silently unless something holds them to the same answers. These are those answers.
 *
 * <p>It used to have to exist <em>three</em> times. {@code RecipeScaler} kept its own copy of the
 * unit choice and was fixed on 2026-09-10 by being made to call {@link Quantities#displayUnit}
 * instead, so there is nothing left on this side to drift. {@link UnitChoice} below is what says so
 * out loud: it runs one table of vectors through both callers, so re-inlining the rule into
 * {@code RecipeScaler} — or fixing one of them and not the other, which is the whole history of this
 * file — fails here rather than on somebody's screen.
 */
class QuantitiesTest {

	private static BigDecimal n(String value) {
		return new BigDecimal(value);
	}

	@Nested
	@DisplayName("the ledger form — exact, because somebody reconciles against it")
	class Ledger {

		@Test
		@DisplayName("steps down into the smaller unit rather than printing a fraction")
		void stepsDown() {
			assertThat(Quantities.exact(n("0.6"), Unit.KG)).isEqualTo("600 gm");
			assertThat(Quantities.exact(n("0.6"), Unit.L)).isEqualTo("600 ml");
			assertThat(Quantities.exact(n("0.02"), Unit.KG)).isEqualTo("20 gm");
			assertThat(Quantities.exact(n("0.2"), Unit.L)).isEqualTo("200 ml");
			assertThat(Quantities.exact(n("0.15"), Unit.KG)).isEqualTo("150 gm");
		}

		@Test
		@DisplayName("promotes into the larger unit once there is a whole one of them")
		void promotes() {
			assertThat(Quantities.exact(n("173542"), Unit.ML)).isEqualTo("173.542 L");
			assertThat(Quantities.exact(n("1500"), Unit.GM)).isEqualTo("1.5 Kg");
			assertThat(Quantities.exact(n("999"), Unit.GM)).isEqualTo("999 gm");
			assertThat(Quantities.exact(n("5"), Unit.KG)).isEqualTo("5 Kg");
		}

		@Test
		@DisplayName("leaves a count alone — it is a whole thing measured in itself")
		void countsAreLeftAlone() {
			assertThat(Quantities.exact(n("3"), Unit.PIECES)).isEqualTo("3 pieces");
		}

		@Test
		@DisplayName("keeps the exact figure, so inventory rows still add up to the balance")
		void keepsTheExactFigure() {
			// E3-S1: "stock shown always equals the sum of movements". Rounding these independently
			// would stop the rows summing to the total on the one screen whose job is that they do.
			assertThat(Quantities.exact(n("10.08"), Unit.KG)).isEqualTo("10.08 Kg");
			assertThat(Quantities.exact(n("134.4"), Unit.GM)).isEqualTo("134.4 gm");
		}

		@Test
		@DisplayName("says nothing rather than zero when there is no figure")
		void nothingIsNotZero() {
			assertThat(Quantities.exact(null, Unit.L)).isEqualTo("—");
			assertThat(Quantities.exact(n("5"), (Unit) null)).isEqualTo("—");
			assertThat(Quantities.exact(n("5"), "FURLONGS")).isEqualTo("—");
		}

		@Test
		@DisplayName("says zero in the unit the thing is kept in")
		void zeroKeepsItsOwnUnit() {
			// Curd's stock page read "0 ml" on hand against a reorder level of 15 L, on an item kept
			// in litres (staging, 2026-09-09). The step-down rule exists to stop a fraction being
			// printed — 0.6 Kg is 600 gm — and zero has no fraction to step away from, so all the
			// rule did there was change the subject and make the reader convert before they could
			// compare the two figures in front of them.
			//
			// The frontend was fixed first and this copy was not, so for a day the screen said
			// "0 L" and the job card in the cook's hand said "0 ml" for the same ingredient. These
			// are the same seven vectors the frontend table carries, word for word, and they are
			// here so that the next time one side moves the other one fails.
			assertThat(Quantities.exact(n("0"), Unit.L)).isEqualTo("0 L");
			assertThat(Quantities.exact(n("0"), Unit.KG)).isEqualTo("0 Kg");
			assertThat(Quantities.exact(n("0"), Unit.ML)).isEqualTo("0 ml");
			assertThat(Quantities.exact(n("0"), Unit.GM)).isEqualTo("0 gm");
			assertThat(Quantities.exact(n("0"), Unit.PIECES)).isEqualTo("0 pieces");
			// And the cook's form says it the same way — a job card line of nothing is still nothing
			// of whatever the recipe measures in.
			assertThat(Quantities.cooks(n("0"), Unit.L)).isEqualTo("0 L");
			assertThat(Quantities.cooks(n("0"), Unit.KG)).isEqualTo("0 Kg");
		}

		@Test
		@DisplayName("a zero that arrived from the database, scale and all, is still a zero")
		void zeroAtAnyScale() {
			// This side has a hazard the frontend does not: a quantity read back through JDBC
			// carries its column's scale, so a genuine nothing arrives as "0.000" rather than as
			// "0". BigDecimal.equals() compares scale as well as value and would answer false to
			// every one of these, which is why the rule above tests signum() instead.
			assertThat(Quantities.exact(n("0.000"), Unit.L)).isEqualTo("0 L");
			assertThat(Quantities.exact(n("0.00000"), Unit.KG)).isEqualTo("0 Kg");
			assertThat(Quantities.cooks(n("0.000"), Unit.L)).isEqualTo("0 L");
			assertThat(Quantities.exact(BigDecimal.ZERO.setScale(6), Unit.ML)).isEqualTo("0 ml");
		}

		@Test
		@DisplayName("a figure that is merely small still steps down, so zero is the only exception")
		void nearlyZeroStillStepsDown() {
			// The guard is for zero exactly and nothing wider. A tenth of a millilitre is a
			// fraction, and a fraction is what the step-down rule is for — if this ever answered
			// "0.0001 L" the fix would have been written as "small numbers keep their unit", which
			// is a different and wrong rule.
			assertThat(Quantities.exact(n("0.0001"), Unit.L)).isEqualTo("0.1 ml");
			assertThat(Quantities.exact(n("0.000001"), Unit.KG)).isEqualTo("0.001 gm");
		}
	}

	@Nested
	@DisplayName("the cook's form — rounded, because somebody weighs against it")
	class Cooks {

		@Test
		@DisplayName("rounds the way a person would, on a step that grows with the number")
		void roundsLikeAPerson() {
			// Rajeev's own five, 2026-08-30. "10.08 KG and 10 KG are the same for practical cooking
			// purposes. We are not measuring gold here."
			assertThat(Quantities.cooks(n("10.08"), Unit.KG)).isEqualTo("10 Kg");
			assertThat(Quantities.cooks(n("134.4"), Unit.GM)).isEqualTo("135 gm");
			assertThat(Quantities.cooks(n("50.4"), Unit.GM)).isEqualTo("50 gm");
			assertThat(Quantities.cooks(n("5.04"), Unit.GM)).isEqualTo("5 gm");
			assertThat(Quantities.cooks(n("840"), Unit.GM)).isEqualTo("840 gm");
		}

		@Test
		@DisplayName("keeps half a gram where half a gram is the honest step")
		void halfGrams() {
			assertThat(Quantities.cooks(n("4.7"), Unit.GM)).isEqualTo("4.5 gm");
			assertThat(Quantities.cooks(n("0.3"), Unit.GM)).isEqualTo("0.3 gm");
		}

		@Test
		@DisplayName("picks the readable unit first and rounds second")
		void unitThenRounding() {
			assertThat(Quantities.cooks(n("0.1344"), Unit.KG)).isEqualTo("135 gm");
			assertThat(Quantities.cooks(n("0.6"), Unit.KG)).isEqualTo("600 gm");
			assertThat(Quantities.cooks(n("173542"), Unit.ML)).isEqualTo("175 L");
		}

		@Test
		@DisplayName("promotes again when rounding carries it over a whole unit")
		void roundingCanPromote() {
			assertThat(Quantities.cooks(n("999.6"), Unit.GM)).isEqualTo("1 Kg");
		}

		@Test
		@DisplayName("never gives half a piece")
		void countsStayWhole() {
			assertThat(Quantities.cooks(n("3.4"), Unit.PIECES)).isEqualTo("3 pieces");
		}
	}

	/**
	 * One table of unit-choice vectors, run through every implementation of the rule that still picks
	 * a unit on this side of the wire. There are two callers and one rule; before 2026-09-10 there
	 * were two callers and two rules, which is how the recipe scale preview came to say "0 ml" for an
	 * ingredient kept in litres after the stock screen and the job card had both been fixed.
	 *
	 * <p>The vectors are the ledger form's, because the ledger form does no rounding and so its unit
	 * <em>is</em> the chosen unit. The cook's form is deliberately not in this table: it can promote a
	 * second time after rounding — 999 gm rounds to 1000 gm, which is a kilo and says so — and that
	 * carry is a rule of its own, covered by {@link Cooks#roundingCanPromote()}.
	 */
	@Nested
	@DisplayName("which unit a figure is said in — one table, every implementation that picks one")
	class UnitChoice {

		/** A quantity as it is stored, and the unit it has to be said in. */
		record Vector(String value, Unit stored, Unit said) {
		}

		private List<Vector> table() {
			return List.of(
					// Nothing is said in the unit the thing is kept in. This is the vector the third
					// copy of the rule did not have, and the defect it did not have it for.
					new Vector("0", Unit.L, Unit.L),
					new Vector("0", Unit.KG, Unit.KG),
					new Vector("0", Unit.ML, Unit.ML),
					new Vector("0", Unit.GM, Unit.GM),
					new Vector("0", Unit.PIECES, Unit.PIECES),
					// ...including a nothing that came back from JDBC wearing its column's scale.
					new Vector("0.000", Unit.L, Unit.L),
					new Vector("0.00000", Unit.KG, Unit.KG),
					// A figure that is merely small still steps down: zero is the only exception.
					new Vector("0.0001", Unit.L, Unit.ML),
					new Vector("0.6", Unit.KG, Unit.GM),
					new Vector("0.2", Unit.L, Unit.ML),
					// The boundary, from both sides of it.
					new Vector("999", Unit.GM, Unit.GM),
					new Vector("1000", Unit.GM, Unit.KG),
					new Vector("1500", Unit.GM, Unit.KG),
					new Vector("2", Unit.KG, Unit.KG),
					new Vector("2000", Unit.ML, Unit.L),
					new Vector("173542", Unit.ML, Unit.L),
					// A count has no sibling to be moved into, at any size.
					new Vector("3", Unit.PIECES, Unit.PIECES));
		}

		@Test
		@DisplayName("Quantities says every one of them in that unit")
		void quantitiesAgrees() {
			for (Vector v : table()) {
				assertThat(Quantities.exact(n(v.value()), v.stored()))
						.as("%s %s", v.value(), v.stored())
						.endsWith(" " + v.said().label());
			}
		}

		@Test
		@DisplayName("and so does the recipe scale preview, because it asks Quantities")
		void recipeScalerAgrees() {
			for (Vector v : table()) {
				ScaledQuantity q = RecipeScaler.scale(n(v.value()), v.stored(), BigDecimal.ONE);
				assertThat(q.displayUnit()).as("%s %s scaled 1:1", v.value(), v.stored())
						.isEqualTo(v.said().label());
			}
		}

		@Test
		@DisplayName("a recipe line of nothing, scaled to a festival, is still nothing of what it is measured in")
		void zeroSurvivesTheScale() {
			// The surface: the scale preview on a recipe page renders displayQuantity and displayUnit
			// side by side, straight from these two fields, with no formatter in between. A line of
			// zero litres read "0 ml" there until this change.
			ScaledQuantity q = RecipeScaler.scale(n("0"), Unit.L, new BigDecimal("500"));
			assertThat(q.displayQuantity()).isEqualByComparingTo("0");
			assertThat(q.displayUnit()).isEqualTo("L");
		}
	}

	/**
	 * The "1 pieces" table (T-144), mirroring {@code __tests__/quantities.test.ts} line for line.
	 *
	 * <p>T-108 fixed this on the screens. It could not reach here, so for one wave the stock page
	 * said "1 piece" and the job card a cook was holding said "1 pieces" — two answers to one
	 * question, about the same stool, on the same day. Every assertion below has a twin in the
	 * TypeScript table with the same input and the same expected string; adding to one and not the
	 * other is how these two files drifted over zero in September, silently, with both suites green.
	 */
	@Nested
	@DisplayName("a count agreeing with its number — \"1 piece\", not \"1 pieces\"")
	class OneOfAThing {

		@Test
		@DisplayName("one of a counted thing is said in the singular, in both forms")
		void oneIsSingular() {
			// What Rajeev saw: one plastic stool on a purchase order, printed "1 pieces".
			assertThat(Quantities.exact(n("1"), Unit.PIECES)).isEqualTo("1 piece");
			assertThat(Quantities.cooks(n("1"), Unit.PIECES)).isEqualTo("1 piece");
		}

		@Test
		@DisplayName("a quantity that becomes one by rounding is said in the singular too")
		void roundingDownToOneIsSingular() {
			// The word is chosen from the figure as SHOWN, not as stored — which is the whole reason
			// say() picks it and the caller does not. The cook's form makes 1.2 stools a whole stool
			// first, so the sheet must read "1 piece" and not "1 pieces".
			assertThat(Quantities.cooks(n("1.2"), Unit.PIECES)).isEqualTo("1 piece");

			// And the ledger form does not round, so the same input keeps its plural there. Both are
			// right; they are answering different questions.
			assertThat(Quantities.exact(n("1.2"), Unit.PIECES)).isEqualTo("1.2 pieces");
		}

		@Test
		@DisplayName("a quantity of exactly one arriving from the database is still one")
		void scaleDoesNotDefeatIt() {
			// JDBC hands back a quantity scaled to its column, so a genuine one stool is "1.000".
			// BigDecimal.equals compares the scale as well as the value and would answer false here,
			// printing "1 pieces" for every row that had ever been near a database. compareTo does not.
			assertThat(Quantities.exact(n("1.000"), Unit.PIECES)).isEqualTo("1 piece");
			assertThat(Quantities.cooks(n("1.00"), Unit.PIECES)).isEqualTo("1 piece");
		}

		@Test
		@DisplayName("taking one back out again reads as one, not as minus one pieces")
		void reversalOfOneIsSingular() {
			assertThat(Quantities.exact(n("-1"), Unit.PIECES)).isEqualTo("-1 piece");
		}

		@Test
		@DisplayName("every other number of them stays plural, including none and a fraction")
		void everythingElseIsPlural() {
			assertThat(Quantities.exact(n("0"), Unit.PIECES)).isEqualTo("0 pieces");
			assertThat(Quantities.exact(n("2"), Unit.PIECES)).isEqualTo("2 pieces");
			assertThat(Quantities.exact(n("1.5"), Unit.PIECES)).isEqualTo("1.5 pieces");
			assertThat(Quantities.cooks(n("3.4"), Unit.PIECES)).isEqualTo("3 pieces");
		}

		@Test
		@DisplayName("a mass or a volume is an abbreviation and never takes an s")
		void abbreviationsDoNotPluralise() {
			// "1 Kgs" is not English, in India or anywhere else. This is the half of the rule that is
			// easy to get wrong in the other direction, by pluralising everything on a count of one.
			assertThat(Quantities.exact(n("1"), Unit.KG)).isEqualTo("1 Kg");
			assertThat(Quantities.exact(n("1"), Unit.GM)).isEqualTo("1 gm");
			assertThat(Quantities.exact(n("1"), Unit.L)).isEqualTo("1 L");
			assertThat(Quantities.exact(n("1"), Unit.ML)).isEqualTo("1 ml");

			// And the same on the two figures that land on exactly one by being promoted into it,
			// which is the path an abbreviation actually reaches a count of one by.
			assertThat(Quantities.exact(n("1000"), Unit.GM)).isEqualTo("1 Kg");
			assertThat(Quantities.exact(n("1000"), Unit.ML)).isEqualTo("1 L");
		}

		@Test
		@DisplayName("naming the unit with no number beside it is still plural, deliberately")
		void theLabelAloneIsUntouched() {
			// A column heading, a dropdown option. T-108 left 22 such call sites alone on the screens
			// for this reason and this side must match: "pieces" is the name of the unit, and only a
			// phrase with a figure in it has anything to agree with. (A rate after a price used to be
			// listed here too. It is not a no-count case — "per piece" is a count of one — and since
			// T-148 the purchase-order sheet asks label(BigDecimal.ONE) for it.)
			assertThat(Unit.PIECES.label()).isEqualTo("pieces");
			assertThat(Unit.PIECES.label(null)).isEqualTo("pieces");
		}
	}

	@Test
	@DisplayName("a quantity of a lakh or more is grouped the Indian way: 1,50,000 pieces (T-279)")
	void lakhsAreGroupedTheIndianWay() {
		// F6. The JDK's en-IN NumberFormat, which say() used, groups in threes: a bulk order of leaf
		// plates printed "150,000 pieces" on the sheet while the screen said "1,50,000 pieces".
		// Mirrored in frontend/__tests__/money-indian-grouping.test.ts, as this file's rule asks.
		assertThat(Quantities.exact(n("99999"), Unit.PIECES)).isEqualTo("99,999 pieces");
		assertThat(Quantities.exact(n("150000"), Unit.PIECES)).isEqualTo("1,50,000 pieces");
		assertThat(Quantities.cooks(n("150000"), Unit.PIECES)).isEqualTo("1,50,000 pieces");
		assertThat(Quantities.exact(n("1234567.5"), Unit.KG)).isEqualTo("12,34,567.5 Kg");
		assertThat(Quantities.exact(n("10000000"), Unit.L)).isEqualTo("1,00,00,000 L");
		assertThat(Quantities.exact(n("-150000"), Unit.KG)).isEqualTo("-1,50,000 Kg");
	}

	@Test
	@DisplayName("rounding cannot compound, because it happens last")
	void roundingCannotCompound() {
		// The worry Rajeev raised: "rounding can add a bigger than expected error". It can — if you
		// round and then compute. Each line is rounded for display only; a total is summed from the
		// stored values and rounded once, at the end.
		String[] lines = {
			"0.1344", "0.0504", "0.00504", "0.84", "1.2", "0.333",
			"2.5", "0.075", "0.019", "4.2", "0.66", "0.008"
		};

		BigDecimal exactTotal = BigDecimal.ZERO;
		for (String line : lines) {
			exactTotal = exactTotal.add(n(line));
		}

		assertThat(Quantities.cooks(exactTotal, Unit.KG)).isEqualTo("10 Kg");
		assertThat(Quantities.cooks(n(lines[0]), Unit.KG)).isEqualTo("135 gm");
		assertThat(Quantities.cooks(n(lines[2]), Unit.KG)).isEqualTo("5 gm");
	}

	/**
	 * Several figures about one thing, said in one unit (T-364) — the ledger form.
	 *
	 * <p>Every assertion in this class has a twin in {@code frontend/__tests__/quantities.test.ts},
	 * under "several figures about one thing, said in one unit", with the same inputs and the same
	 * expected strings. That is the standing arrangement for this file and it is the only thing
	 * standing between the two implementations and a silent disagreement: the copies drifted over
	 * zero in September with both suites green, because no table on either side held a zero. A vector
	 * added to one of these tables is not optional in the other.
	 *
	 * <p>The backend has no production caller of the ledger form — the printed documents all use the
	 * cook's form in {@link SetOfFiguresCooks}. It exists so this table can be run here at all: the
	 * TypeScript table is written against the ledger form, whose unit <em>is</em> the chosen unit
	 * because it rounds nothing.
	 */
	@Nested
	@DisplayName("several figures about one thing, said in one unit — the ledger form")
	class SetOfFigures {

		private List<BigDecimal> figures(String... values) {
			List<BigDecimal> out = new java.util.ArrayList<>();
			for (String value : values) {
				out.add(value == null ? null : n(value));
			}
			return out;
		}

		@Test
		@DisplayName("leaves exact() alone — a figure on its own is still promoted and demoted as before")
		void leavesTheSingleFigureFormAlone() {
			assertThat(Quantities.exact(n("0.02"), Unit.KG)).isEqualTo("20 gm");
			assertThat(Quantities.exact(n("2.06"), Unit.KG)).isEqualTo("2.06 Kg");
		}

		@Test
		@DisplayName("says the row Rajeev found in one unit, and available no longer switches to grams")
		void theRowRajeevFound() {
			var say = Quantities.oneUnitFor(Unit.KG, figures("2.06", "2.04", "0.02"));
			assertThat(say.apply(n("2.06"))).isEqualTo("2.06 Kg");
			assertThat(say.apply(n("2.04"))).isEqualTo("2.04 Kg");
			assertThat(say.apply(n("0.02"))).isEqualTo("0.02 Kg");
		}

		@Test
		@DisplayName("takes its unit from the biggest figure, so a small set stays in the small unit")
		void theBiggestFigureChooses() {
			// Nothing here is a kilogram's worth, so kilograms would print three leading zeroes on
			// every figure. The largest figure is what says how big the quantities in this set are.
			var say = Quantities.oneUnitFor(Unit.KG, figures("0.85", "0.35", "0.02"));
			assertThat(say.apply(n("0.85"))).isEqualTo("850 gm");
			assertThat(say.apply(n("0.02"))).isEqualTo("20 gm");
		}

		@Test
		@DisplayName("promotes the whole set as soon as one figure is a kilogram")
		void oneWholeKilogramPromotesTheSet() {
			var say = Quantities.oneUnitFor(Unit.KG, figures("1.2", "0.85", "0.35"));
			assertThat(say.apply(n("1.2"))).isEqualTo("1.2 Kg");
			assertThat(say.apply(n("0.85"))).isEqualTo("0.85 Kg");
		}

		@Test
		@DisplayName("keeps the stored unit when every figure is nothing, as a lone zero already does")
		void allZeroKeepsTheStoredUnit() {
			assertThat(Quantities.oneUnitFor(Unit.L, figures("0", "0", "0")).apply(n("0")))
					.isEqualTo("0 L");
			assertThat(Quantities.exact(n("0"), Unit.L)).isEqualTo("0 L");
		}

		@Test
		@DisplayName("never prints a figure that exists as a zero, however small it is beside the others")
		void aSmallFigureDoesNotVanish() {
			// 0.4 gm forced into kilograms is 0.0004, which does not fit the three decimals a ledger
			// figure is given — and "0 Kg" would say the shelf is empty when it is not.
			var say = Quantities.oneUnitFor(Unit.KG, figures("500", "0.0004"));
			assertThat(say.apply(n("0.0004"))).isEqualTo("0.0004 Kg");
			assertThat(say.apply(n("500"))).isEqualTo("500 Kg");
		}

		@Test
		@DisplayName("rounds nothing away — this is the ledger form and the figures have to add up")
		void roundsNothingAway() {
			// Found by measuring the real item screen: a draw of 418.2 gm beside a lot of 4.664 Kg.
			// Three decimals is a gram of a kilo, so it would have printed 0.418 Kg and lost two
			// hundred milligrams out of a figure somebody reconciles against.
			var say = Quantities.oneUnitFor(Unit.GM, figures("4664", "418.2"));
			assertThat(say.apply(n("418.2"))).isEqualTo("0.4182 Kg");
			assertThat(say.apply(n("4664"))).isEqualTo("4.664 Kg");
		}

		@Test
		@DisplayName("carries a negative through on the set's scale — available may be less than nothing")
		void negativesRideTheSameScale() {
			var say = Quantities.oneUnitFor(Unit.KG, figures("1.96", "2.04", "-0.08"));
			assertThat(say.apply(n("-0.08"))).isEqualTo("-0.08 Kg");
		}

		@Test
		@DisplayName("has nothing to convert for a count, and hands back what exact() would say")
		void countsHaveNothingToConvert() {
			var say = Quantities.oneUnitFor(Unit.PIECES, figures("1200", "1"));
			assertThat(say.apply(n("1200"))).isEqualTo("1,200 pieces");
			assertThat(say.apply(n("1"))).isEqualTo("1 piece");
		}

		@Test
		@DisplayName("says a figure nobody has with a dash, as exact() does")
		void aMissingFigureIsADash() {
			assertThat(Quantities.oneUnitFor(Unit.KG, figures("5")).apply(null)).isEqualTo("—");
		}

		@Test
		@DisplayName("is not thrown by a set where every figure is missing")
		void everyFigureMissing() {
			assertThat(Quantities.oneUnitFor(Unit.KG, figures(null, null)).apply(n("2")))
					.isEqualTo("2 Kg");
		}
	}

	/**
	 * The same rule in the cook's form — what the job card, work order and recipe card print (T-364).
	 *
	 * <p>There is no twin for this class in TypeScript, and that is deliberate rather than drift:
	 * {@code format.ts} has only the ledger form, because the screens that needed one unit are ledger
	 * screens. A printed document is weighed against instead. The unit choice is the same rule from
	 * the same biggest figure — {@link SetOfFigures} above is what pins that — and only the rounding
	 * is added, exactly as {@link Quantities#cooks} adds it to {@link Quantities#exact}.
	 */
	@Nested
	@DisplayName("several figures about one thing, said in one unit — the cook's form")
	class SetOfFiguresCooks {

		private List<BigDecimal> figures(String... values) {
			List<BigDecimal> out = new java.util.ArrayList<>();
			for (String value : values) {
				out.add(n(value));
			}
			return out;
		}

		@Test
		@DisplayName("a work order line and the lots it comes from add up on the page: 2.5 Kg + 0.5 Kg")
		void aLineAndItsLotsAddUp() {
			// Before this, the half-kilo lot printed "500 gm" beside a total of "3 Kg", so the one
			// row that exists to be checked against itself had to be converted first.
			var say = Quantities.cooksOneUnitFor(Unit.KG, figures("3", "2.5", "0.5"));
			assertThat(say.apply(n("3"))).isEqualTo("3 Kg");
			assertThat(say.apply(n("2.5"))).isEqualTo("2.5 Kg");
			assertThat(say.apply(n("0.5"))).isEqualTo("0.5 Kg");
		}

		@Test
		@DisplayName("a shortfall pair is one unit: 0.8 Kg / 12 Kg, never 800 gm / 12 Kg")
		void aShortfallPairIsOneUnit() {
			var say = Quantities.cooksOneUnitFor(Unit.KG, figures("0.8", "12"));
			assertThat("%s / %s".formatted(say.apply(n("0.8")), say.apply(n("12"))))
					.isEqualTo("0.8 Kg / 12 Kg");
		}

		@Test
		@DisplayName("a scaled yield and its base are one unit: 2 L made from 0.5 L, not from 500 ml")
		void aScaledYieldAndItsBase() {
			var say = Quantities.cooksOneUnitFor(Unit.L, figures("2", "0.5"));
			assertThat("Scaled to %s (base %s)".formatted(say.apply(n("2")), say.apply(n("0.5"))))
					.isEqualTo("Scaled to 2 L (base 0.5 L)");
		}

		@Test
		@DisplayName("a figure is rounded at its own scale, so 8 gm in a kilogram row is not lost")
		void eachFigureIsRoundedAtItsOwnScale() {
			// The trap this rule exists to avoid. The set is said in kilograms because 12 is what
			// says how big it is; rounding 0.008 as a kilogram figure would round it to a tenth of a
			// kilo and print "0 Kg" — a lot somebody is being sent to fetch, reported as nothing.
			var say = Quantities.cooksOneUnitFor(Unit.KG, figures("12", "0.008"));
			assertThat(say.apply(n("12"))).isEqualTo("12 Kg");
			assertThat(say.apply(n("0.008"))).isEqualTo("0.008 Kg");
		}

		@Test
		@DisplayName("a set of one is said exactly as cooks() says it: a 0.1344 Kg line is still 135 gm")
		void aSetOfOneIsJustTheCooksForm() {
			// The one-line work order sheet. The set does not stop the rounding; it only fixes which
			// word the figures share, and one figure shares it with nobody.
			assertThat(Quantities.cooksOneUnitFor(Unit.KG, figures("0.1344")).apply(n("0.1344")))
					.isEqualTo("135 gm");
			assertThat(Quantities.cooks(n("0.1344"), Unit.KG)).isEqualTo("135 gm");
		}

		@Test
		@DisplayName("rounding that carries over a thousand does not move the set's unit")
		void theCarryDoesNotMoveTheSetsUnit() {
			// cooks() promotes a lone 999.6 gm to "1 Kg" after rounding. In a set said in grams the
			// word is already chosen, and the carry is only arithmetic: 1,000 gm, and the figure
			// beside it stays comparable.
			var say = Quantities.cooksOneUnitFor(Unit.GM, figures("999.6", "400"));
			assertThat(say.apply(n("999.6"))).isEqualTo("1,000 gm");
			assertThat(say.apply(n("400"))).isEqualTo("400 gm");
			assertThat(Quantities.cooks(n("999.6"), Unit.GM)).isEqualTo("1 Kg");
		}

		@Test
		@DisplayName("a unit that is not a unit falls back to a dash, as cooks() does")
		void anUnknownStoredUnitNameIsADash() {
			assertThat(Quantities.cooksOneUnitFor("NOT_A_UNIT", figures("2")).apply(n("2")))
					.isEqualTo("—");
			assertThat(Quantities.cooks(n("2"), "NOT_A_UNIT")).isEqualTo("—");
		}
	}
}
