package org.iskcon.kms.shoppinglist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.iskcon.kms.ingredient.Quantities;
import org.iskcon.kms.ingredient.Unit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The buying steps (T-243). Rajeev's examples are the first three rows; the rest are the edges of
 * each band — exactly on a boundary, and a hair over it — because a boundary is where a table like
 * this is most likely to be wrong, and "never rounds down" is only proven by the rows where rounding
 * down would have been the nearer answer.
 */
class BuyingAmountTest {

	@ParameterizedTest(name = "{0} {1} is bought as {2} {1}")
	@CsvSource({
			// Rajeev's own examples, 2026-09-19.
			"2792,    GM, 3000",
			"430,     GM, 450",
			"12200,   GM, 13000",
			// Under 1 Kg: the next 50 gm.
			"1,       GM, 50",
			"50,      GM, 50",
			"51,      GM, 100",
			"999,     GM, 1000",
			// Exactly 1 Kg stays 1 Kg; a gram over moves to half-kilo steps.
			"1000,    GM, 1000",
			"1001,    GM, 1500",
			"2501,    GM, 3000",
			// Exactly 10 Kg stays 10 Kg; over it, whole kilos.
			"10000,   GM, 10000",
			"10001,   GM, 11000",
			// Exactly 100 Kg stays 100 Kg; over it, five kilos at a time.
			"100000,  GM, 100000",
			"100001,  GM, 105000",
			"102000,  GM, 105000",
			// Volume uses the identical table.
			"2792,    ML, 3000",
			"430,     ML, 450",
			// An ingredient stored in the large unit: the same need, the same answer.
			"2.792,   KG, 3",
			"12.2,    KG, 13",
			"0.43,    KG, 0.45",
			"101,     KG, 105",
			"2.3,     L,  2.5",
			// Pieces: whole things, up.
			"2.1,     PIECES, 3",
			"4,       PIECES, 4",
	})
	void roundsUpToTheBuyingStep(String needed, Unit unit, String expected) {
		BigDecimal bought = BuyingAmount.of(new BigDecimal(needed), unit, List.of());
		assertThat(bought).isEqualByComparingTo(expected);
	}

	@Test
	@DisplayName("never rounds down, at any quantity in any band")
	void neverRoundsDown() {
		for (int g = 1; g <= 250_000; g += 7) {
			BigDecimal needed = BigDecimal.valueOf(g);
			BigDecimal bought = BuyingAmount.of(needed, Unit.GM, List.of());
			assertThat(bought).as("%d gm", g).isGreaterThanOrEqualTo(needed);
			// And adds less than one step — never a whole extra step on a figure already on one.
			assertThat(bought.subtract(needed)).as("%d gm", g)
					.isLessThan(BuyingAmount.stepFor(needed));
		}
	}

	@Test
	@DisplayName("a fractional need just over a boundary still goes up")
	void fractionJustOverABoundary() {
		assertThat(BuyingAmount.of(new BigDecimal("1000.001"), Unit.GM, List.of())).isEqualByComparingTo("1500");
		assertThat(BuyingAmount.of(new BigDecimal("0.001"), Unit.GM, List.of())).isEqualByComparingTo("50");
	}

	@Test
	@DisplayName("nothing needed is nothing bought")
	void zeroStaysZero() {
		assertThat(BuyingAmount.of(BigDecimal.ZERO, Unit.GM, List.of())).isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("a buying amount reads unchanged through the cook's form — 3000 gm says 3 Kg")
	void readsCleanlyThroughTheCooksForm() {
		assertThat(Quantities.cooks(BuyingAmount.of(new BigDecimal("2792"), Unit.GM, List.of()), Unit.GM))
				.isEqualTo("3 Kg");
		assertThat(Quantities.cooks(BuyingAmount.of(new BigDecimal("430"), Unit.GM, List.of()), Unit.GM))
				.isEqualTo("450 gm");
		assertThat(Quantities.cooks(BuyingAmount.of(new BigDecimal("12200"), Unit.GM, List.of()), Unit.GM))
				.isEqualTo("13 Kg");
		assertThat(Quantities.cooks(BuyingAmount.of(new BigDecimal("1001"), Unit.ML, List.of()), Unit.ML))
				.isEqualTo("1.5 L");
	}

	@Test
	@DisplayName("pack sizes are not guessed at: a non-empty list is refused, not ignored")
	void packSizesAreRefusedUntilTheRuleExists() {
		assertThatThrownBy(() -> BuyingAmount.of(
				new BigDecimal("300"), Unit.GM, List.of(new BigDecimal("250"), new BigDecimal("500"))))
				.isInstanceOf(UnsupportedOperationException.class);
	}
}
