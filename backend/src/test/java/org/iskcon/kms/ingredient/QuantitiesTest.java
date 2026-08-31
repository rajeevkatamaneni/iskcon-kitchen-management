package org.iskcon.kms.ingredient;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
		@DisplayName("leaves counts alone — they are whole things measured in themselves")
		void countsAreLeftAlone() {
			assertThat(Quantities.exact(n("3"), Unit.PIECES)).isEqualTo("3 pieces");
			assertThat(Quantities.exact(n("100"), Unit.SERVINGS)).isEqualTo("100 servings");
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
		@DisplayName("never gives half a piece or half a person")
		void countsStayWhole() {
			assertThat(Quantities.cooks(n("3.4"), Unit.PIECES)).isEqualTo("3 pieces");
			assertThat(Quantities.cooks(n("99.6"), Unit.SERVINGS)).isEqualTo("100 servings");
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
