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
}
