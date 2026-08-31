package org.iskcon.kms.library;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The parsing rules, on the strings the books actually contain.
 *
 * <p>Every example here is copied from a real recipe rather than invented, because the whole risk in
 * this parser is the shapes a human writing a cookbook thought were obvious.
 */
class BookParserTest {

	@Nested
	@DisplayName("yields")
	class Yields {

		@Test
		@DisplayName("a plain volume and a plain mass")
		void plain() {
			assertThat(BookParser.parseYield("20 L")).hasValueSatisfying(q -> {
				assertThat(q.value()).isEqualByComparingTo("20");
				assertThat(q.unit()).isEqualTo("L");
			});
			assertThat(BookParser.parseYield("12 Kg")).hasValueSatisfying(q -> {
				assertThat(q.value()).isEqualByComparingTo("12");
				assertThat(q.unit()).isEqualTo("KG");
			});
		}

		@Test
		@DisplayName("an approximation is a number with a tilde in front of it")
		void approximate() {
			assertThat(BookParser.parseYield("~10 Kg finished (100 gm per devotee)"))
					.hasValueSatisfying(q -> {
						assertThat(q.value()).isEqualByComparingTo("10");
						assertThat(q.unit()).isEqualTo("KG");
					});
		}

		@Test
		@DisplayName("anything that is not a mass or a volume is a count, whatever it is called")
		void countNouns() {
			// 137 distinct nouns appear across the books. None of them is in a dictionary here, and
			// that is the point: a new book bringing a new sweet must not need a code change.
			for (String text : new String[] {
					"300 idlis (3 per devotee)", "200 mudde (2 per devotee)", "150 pakore",
					"~250 pieces", "400 bobbatlu", "36 laddu (2 per devotee)"}) {
				assertThat(BookParser.parseYield(text))
						.as(text)
						.hasValueSatisfying(q -> assertThat(q.unit()).isEqualTo("PIECES"));
			}
		}

		@Test
		@DisplayName("grams and millilitres are folded into the canonical unit")
		void folded() {
			assertThat(BookParser.parseYield("500 gm")).hasValueSatisfying(q ->
					assertThat(q.value()).isEqualByComparingTo("0.5"));
			assertThat(BookParser.parseYield("750 ml")).hasValueSatisfying(q ->
					assertThat(q.value()).isEqualByComparingTo("0.75"));
		}

		@Test
		@DisplayName("a yield with no leading number is refused rather than guessed at")
		void refusesNonsense() {
			assertThat(BookParser.parseYield("a big pot")).isEmpty();
			assertThat(BookParser.parseYield("")).isEmpty();
			assertThat(BookParser.parseYield(null)).isEmpty();
		}
	}

	@Nested
	@DisplayName("per-head portions")
	class PerHead {

		@Test
		@DisplayName("from the book's own field, folded into the yield's unit")
		void fromField() {
			// Rasam: 20 L yield, 200 ml a head. 0.2 litres is what lets 300 people become 60 L.
			assertThat(BookParser.perHead("200 ml", "20 L", "L")).hasValueSatisfying(q -> {
				assertThat(q.value()).isEqualByComparingTo("0.2");
				assertThat(q.unit()).isEqualTo("L");
			});
		}

		@Test
		@DisplayName("from the yield's parenthetical where the book left the field empty")
		void fromParenthetical() {
			// Karnataka's Rave Idli carries no `per` at all; the 3 exists only in the yield string.
			assertThat(BookParser.perHead(null, "300 idlis (3 per devotee)", "PIECES"))
					.hasValueSatisfying(q -> {
						assertThat(q.value()).isEqualByComparingTo("3");
						assertThat(q.unit()).isEqualTo("PIECES");
					});
		}

		@Test
		@DisplayName("the field wins over the parenthetical, and in the books they never disagree")
		void fieldWins() {
			assertThat(BookParser.perHead("4 pcs", "400 idlis (4 per devotee)", "PIECES"))
					.hasValueSatisfying(q -> assertThat(q.value()).isEqualByComparingTo("4"));
		}

		@Test
		@DisplayName("a portion in a different family from the yield is discarded, not converted")
		void mismatchedFamily() {
			// Delhi's Papdi, the only one in the library: 5 Kg made, 6 pieces served. There is no
			// arithmetic from a head count to kilograms here, and inventing one would plan 600 Kg
			// of papdi for a hundred people.
			assertThat(BookParser.perHead("6 pcs", "5 Kg", "KG")).isEmpty();
		}

		@Test
		@DisplayName("absent where the book says nothing — the masalas and the pickles")
		void absent() {
			assertThat(BookParser.perHead(null, "~4 Kg", "KG")).isEmpty();
			assertThat(BookParser.perHead("", "12 Kg", "KG")).isEmpty();
		}
	}

	@Nested
	@DisplayName("ingredient quantities")
	class Ingredients {

		@Test
		@DisplayName("the eight unit tokens the books use, and nothing else")
		void units() {
			assertThat(unit("8 L")).isEqualTo("L");
			assertThat(unit("200 gm")).isEqualTo("GM");
			assertThat(unit("3.5 Kg")).isEqualTo("KG");
			assertThat(unit("400 ml")).isEqualTo("ML");
			assertThat(unit("20 nos")).isEqualTo("PIECES");
			assertThat(unit("21 Nos")).isEqualTo("PIECES");
			assertThat(unit("16 pieces")).isEqualTo("PIECES");
			assertThat(unit("3 pcs")).isEqualTo("PIECES");
		}

		@Test
		@DisplayName("a line quantity keeps the unit the cook was given, unfolded")
		void notFolded() {
			// Unlike a yield: 200 gm stays 200 GM, because the catalogue and the scaler both work in
			// the unit the recipe asked in.
			assertThat(BookParser.ingredientQuantity("200 gm"))
					.hasValueSatisfying(q -> assertThat(q.value()).isEqualByComparingTo("200"));
		}

		@Test
		@DisplayName("an unknown token stops the load rather than becoming a count")
		void refusesUnknown() {
			// A yield may name its dish; an ingredient line never does. So "20 wood apples" is a
			// parsing failure here, and the loader is meant to fail on it loudly.
			assertThat(BookParser.ingredientQuantity("20 wood apples")).isEmpty();
			assertThat(BookParser.ingredientQuantity("a handful")).isEmpty();
		}
	}

	private static String unit(String text) {
		Optional<BookParser.LineQuantity> q = BookParser.ingredientQuantity(text);
		assertThat(q).as(text).isPresent();
		return q.orElseThrow().unit();
	}

	@Test
	@DisplayName("decimals survive, because half a kilo of jaggery is a real quantity")
	void decimals() {
		assertThat(BookParser.ingredientQuantity("1.5 Kg"))
				.hasValueSatisfying(q -> assertThat(q.value()).isEqualByComparingTo(new BigDecimal("1.5")));
	}
}
