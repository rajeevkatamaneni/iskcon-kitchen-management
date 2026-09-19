package org.iskcon.kms.shoppinglist;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.ingredient.Quantities;
import org.iskcon.kms.ingredient.Unit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The buying amount (R-SL-2, R-SL-3; T-259).
 *
 * <p>The step table is tested at the boundaries R-SL-2's acceptance names — 999 gm, 1,000 gm, 10 Kg,
 * 100 Kg exactly — and a hair either side of each, because a boundary is where a table like this is
 * most likely to be wrong. "Never rounds down" is proven by a sweep, since only the rows where
 * rounding down would have been the nearer answer can prove it and a sweep has hundreds of them.
 *
 * <p>The pack rule is tested at both settings of {@link BuyingAmount#MIX_PACK_SIZES}, because that
 * switch is provisional pending Rajeev's answer to Q-16 and whichever way he answers, the other
 * setting must already be known to work.
 */
class BuyingAmountTest {

	private static BigDecimal stepped(String needed, Unit unit) {
		return BuyingAmount.of(new BigDecimal(needed), unit, null, List.of()).quantity();
	}

	private static BuyingAmount.Pack pack(String name, String qty, Unit unit) {
		return new BuyingAmount.Pack(UUID.randomUUID(), name, new BigDecimal(qty), unit);
	}

	/** "2 × Bag (25 Kg)" style, so a failure reads as the order would. */
	private static List<String> described(BuyingAmount.Result r) {
		return r.packs().stream().map(p -> p.count() + " × " + p.label()).toList();
	}

	@Nested
	@DisplayName("without pack sizes: the step table")
	class Steps {

		@ParameterizedTest(name = "{0} {1} is bought as {2} {1}")
		@CsvSource({
				// The document's example, and Rajeev's two others from the same review.
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
				"9999,    GM, 10000",
				// Exactly 10 Kg stays 10 Kg; over it, whole kilos.
				"10000,   GM, 10000",
				"10001,   GM, 11000",
				"99999,   GM, 100000",
				// Exactly 100 Kg stays 100 Kg; over it, five kilos at a time.
				"100000,  GM, 100000",
				"100001,  GM, 105000",
				"102000,  GM, 105000",
				// Volume uses the identical table.
				"2792,    ML, 3000",
				"999,     ML, 1000",
				"1000,    ML, 1000",
				"10000,   ML, 10000",
				"100000,  ML, 100000",
				// An ingredient stored in the large unit: the same need, the same answer.
				"0.999,   KG, 1",
				"1,       KG, 1",
				"10,      KG, 10",
				"100,     KG, 100",
				"2.792,   KG, 3",
				"12.2,    KG, 13",
				"0.43,    KG, 0.45",
				"101,     KG, 105",
				"2.3,     L,  2.5",
				// Pieces: the next whole number.
				"2.1,     PIECES, 3",
				"4,       PIECES, 4",
				"0.2,     PIECES, 1",
		})
		void roundsUpToTheBuyingStep(String needed, Unit unit, String expected) {
			BuyingAmount.Result r = BuyingAmount.of(new BigDecimal(needed), unit, null, List.of());
			assertThat(r.quantity()).isEqualByComparingTo(expected);
			assertThat(r.packs()).as("a stepped amount is not in packs").isEmpty();
			assertThat(r.fromVendor()).isFalse();
		}

		@Test
		@DisplayName("never rounds down, at any quantity in any band")
		void neverRoundsDown() {
			for (int g = 1; g <= 250_000; g += 7) {
				BigDecimal needed = BigDecimal.valueOf(g);
				BigDecimal bought = BuyingAmount.of(needed, Unit.GM, null, List.of()).quantity();
				assertThat(bought).as("%d gm", g).isGreaterThanOrEqualTo(needed);
				// And adds less than one step — never a whole extra step on a figure already on one.
				assertThat(bought.subtract(needed)).as("%d gm", g).isLessThan(BuyingAmount.stepFor(needed));
			}
		}

		@Test
		@DisplayName("a fractional need just over a boundary still goes up")
		void fractionJustOverABoundary() {
			assertThat(stepped("1000.001", Unit.GM)).isEqualByComparingTo("1500");
			assertThat(stepped("0.001", Unit.GM)).isEqualByComparingTo("50");
			assertThat(stepped("100.001", Unit.KG)).isEqualByComparingTo("105");
		}

		@Test
		@DisplayName("nothing needed is nothing bought")
		void zeroStaysZero() {
			assertThat(stepped("0", Unit.GM)).isEqualByComparingTo("0");
		}

		@Test
		@DisplayName("a buying amount reads unchanged through the cook's form — 2792 gm reads 3 Kg")
		void readsCleanlyThroughTheCooksForm() {
			assertThat(Quantities.cooks(stepped("2792", Unit.GM), Unit.GM)).isEqualTo("3 Kg");
			assertThat(Quantities.cooks(stepped("430", Unit.GM), Unit.GM)).isEqualTo("450 gm");
			assertThat(Quantities.cooks(stepped("12200", Unit.GM), Unit.GM)).isEqualTo("13 Kg");
			assertThat(Quantities.cooks(stepped("1001", Unit.ML), Unit.ML)).isEqualTo("1.5 L");
		}
	}

	@Nested
	@DisplayName("with the ingredient's pack sizes")
	class IngredientPacks {

		private final List<BuyingAmount.Pack> tea = List.of(
				pack(null, "250", Unit.GM), pack(null, "500", Unit.GM), pack(null, "1", Unit.KG));

		@Test
		@DisplayName("the document's example: tea 416 gm with {250 gm, 500 gm, 1 Kg} is 1 × 500 gm")
		void teaIsOneFiveHundred() {
			for (boolean mix : new boolean[] {true, false}) {
				BuyingAmount.Result r = BuyingAmount.of(new BigDecimal("416"), Unit.GM, null, tea, mix);
				assertThat(described(r)).as("mixing %s", mix).containsExactly("1 × 500 gm");
				assertThat(r.quantity()).isEqualByComparingTo("500");
				assertThat(r.fromVendor()).isFalse();
			}
		}

		@Test
		@DisplayName("mixed (the provisional default): 1,200 gm is 1 × 1 Kg + 1 × 250 gm")
		void mixedTwelveHundred() {
			BuyingAmount.Result r = BuyingAmount.of(new BigDecimal("1200"), Unit.GM, null, tea, true);
			assertThat(described(r)).containsExactly("1 × 1 Kg", "1 × 250 gm");
			assertThat(r.quantity()).isEqualByComparingTo("1250");
		}

		@Test
		@DisplayName("single size: 1,200 gm is 5 × 250 gm — 50 over beats 300 (3 × 500) and 800 (2 × 1 Kg)")
		void singleSizeTwelveHundred() {
			BuyingAmount.Result r = BuyingAmount.of(new BigDecimal("1200"), Unit.GM, null, tea, false);
			assertThat(described(r)).containsExactly("5 × 250 gm");
			assertThat(r.quantity()).isEqualByComparingTo("1250");
		}

		@Test
		@DisplayName("the provisional default is mixing, until Rajeev answers Q-16")
		void defaultIsMixed() {
			assertThat(BuyingAmount.MIX_PACK_SIZES).isTrue();
			assertThat(described(BuyingAmount.of(new BigDecimal("1200"), Unit.GM, null, tea)))
					.containsExactly("1 × 1 Kg", "1 × 250 gm");
		}

		@Test
		@DisplayName("an exact fit takes the fewest packs: 1 Kg is 1 × 1 Kg, not 2 × 500 gm")
		void exactFitFewestPacks() {
			for (boolean mix : new boolean[] {true, false}) {
				assertThat(described(BuyingAmount.of(new BigDecimal("1000"), Unit.GM, null, tea, mix)))
						.as("mixing %s", mix).containsExactly("1 × 1 Kg");
			}
		}

		@Test
		@DisplayName("on an ingredient kept in Kg, the quantity and each pack are in Kg")
		void packsInTheLinesUnit() {
			BuyingAmount.Result r = BuyingAmount.of(new BigDecimal("1.2"), Unit.KG, null, tea, true);
			assertThat(r.quantity()).isEqualByComparingTo("1.25");
			assertThat(r.packs()).extracting(BuyPackView::perPackQty)
					.usingElementComparator(BigDecimal::compareTo)
					.containsExactly(new BigDecimal("1"), new BigDecimal("0.25"));
		}

		@Test
		@DisplayName("never short, and the quantity is always the packs added up, at either setting")
		void neverShortAndAddsUp() {
			List<BuyingAmount.Pack> odd = List.of(
					pack("Bag", "25", Unit.KG), pack(null, "5", Unit.KG), pack(null, "750", Unit.GM));
			for (boolean mix : new boolean[] {true, false}) {
				for (int g = 1; g <= 120_000; g += 997) {
					BigDecimal needed = BigDecimal.valueOf(g);
					BuyingAmount.Result r = BuyingAmount.of(needed, Unit.GM, null, odd, mix);
					assertThat(r.quantity()).as("%d gm, mixing %s", g, mix).isGreaterThanOrEqualTo(needed);
					BigDecimal sum = r.packs().stream()
							.map(p -> p.perPackQty().multiply(BigDecimal.valueOf(p.count())))
							.reduce(BigDecimal.ZERO, BigDecimal::add);
					assertThat(r.quantity()).as("%d gm, mixing %s", g, mix).isEqualByComparingTo(sum);
					// Less than one smallest pack over: otherwise a pack could come out and still cover.
					assertThat(r.quantity().subtract(needed)).as("%d gm, mixing %s", g, mix)
							.isLessThan(mix ? new BigDecimal("750") : new BigDecimal("25000"));
				}
			}
		}

		@Test
		@DisplayName("mixing never leaves more over than the best single size")
		void mixingIsNeverWorse() {
			List<BuyingAmount.Pack> odd = List.of(
					pack("Bag", "25", Unit.KG), pack(null, "5", Unit.KG), pack(null, "750", Unit.GM));
			for (int g = 1; g <= 120_000; g += 997) {
				BigDecimal needed = BigDecimal.valueOf(g);
				BigDecimal mixed = BuyingAmount.of(needed, Unit.GM, null, odd, true).quantity();
				BigDecimal single = BuyingAmount.of(needed, Unit.GM, null, odd, false).quantity();
				assertThat(mixed).as("%d gm", g).isLessThanOrEqualTo(single);
			}
		}
	}

	@Nested
	@DisplayName("with the vendor's \"Sells it as\" pack")
	class VendorPack {

		private final BuyingAmount.Pack bag = pack("Bag", "25", Unit.KG);
		private final List<BuyingAmount.Pack> rice = List.of(pack(null, "5", Unit.KG), bag);

		@Test
		@DisplayName("100 Kg short, sold as Bag = 25 Kg, is 4 bags — R-SL-3's example")
		void fourBags() {
			BuyingAmount.Result r = BuyingAmount.of(new BigDecimal("100"), Unit.KG, bag, rice);
			assertThat(described(r)).containsExactly("4 × Bag (25 Kg)");
			assertThat(r.quantity()).isEqualByComparingTo("100");
			assertThat(r.fromVendor()).isTrue();
			assertThat(r.packs().get(0).packSizeId()).isEqualTo(bag.id());
			assertThat(r.packs().get(0).perPackQty()).isEqualByComparingTo("25");
		}

		@Test
		@DisplayName("only the vendor's pack: 101 Kg is 5 bags, not 4 bags and a 5 Kg")
		void vendorPackOnly() {
			BuyingAmount.Result r = BuyingAmount.of(new BigDecimal("101"), Unit.KG, bag, rice);
			assertThat(described(r)).containsExactly("5 × Bag (25 Kg)");
			assertThat(r.quantity()).isEqualByComparingTo("125");
		}

		@Test
		@DisplayName("on an ingredient kept in gm, the bag is 25000 gm")
		void inGrams() {
			BuyingAmount.Result r = BuyingAmount.of(new BigDecimal("100000"), Unit.GM, bag, List.of(bag));
			assertThat(r.quantity()).isEqualByComparingTo("100000");
			assertThat(r.packs().get(0).perPackQty()).isEqualByComparingTo("25000");
			assertThat(r.packs().get(0).label()).isEqualTo("Bag (25 Kg)");
		}
	}

	@Nested
	@DisplayName("a quantity typed by hand is never re-rounded")
	class Typed {

		private final BuyingAmount.Pack bag = pack("Bag", "25", Unit.KG);

		@Test
		@DisplayName("a whole number of the vendor's pack is described as packs, unchanged")
		void wholeBags() {
			BuyingAmount.Result r = BuyingAmount.describeTyped(new BigDecimal("100"), Unit.KG, bag);
			assertThat(r.quantity()).isEqualByComparingTo("100");
			assertThat(described(r)).containsExactly("4 × Bag (25 Kg)");
			assertThat(r.fromVendor()).isTrue();
		}

		@Test
		@DisplayName("anything else is left exactly as typed, with no packs")
		void notWholeBags() {
			BuyingAmount.Result r = BuyingAmount.describeTyped(new BigDecimal("90"), Unit.KG, bag);
			assertThat(r.quantity()).isEqualByComparingTo("90");
			assertThat(r.packs()).isEmpty();
			assertThat(r.fromVendor()).isFalse();

			BuyingAmount.Result grams = BuyingAmount.describeTyped(new BigDecimal("2792"), Unit.GM, null);
			assertThat(grams.quantity()).isEqualByComparingTo("2792");
			assertThat(grams.packs()).isEmpty();
		}
	}
}
