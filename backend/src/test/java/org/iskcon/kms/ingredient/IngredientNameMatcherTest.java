package org.iskcon.kms.ingredient;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.ingredient.IngredientNameMatcher.Entry;
import org.iskcon.kms.ingredient.IngredientNameMatcher.Match;
import org.iskcon.kms.ingredient.IngredientNameMatcher.MatchKind;
import org.iskcon.kms.ingredient.IngredientNameMatcher.Split;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The rule behind §9A of docs/work/PROCUREMENT-REQUIREMENTS.md: "Curd", "Curd, fresh", "Curd, sour"
 * and "Curd, whisked" were four ingredients, so curd was stocked, priced and bought in four places.
 * These cases are the parsing rule's specification, and the proof for T-249 quotes them. Which words
 * count as preparations is provisional until Rajeev answers Q-12; the cases marked "provisional"
 * are the conductor's ruling, and change with the list if he rules otherwise.
 */
class IngredientNameMatcherTest {

	private static Entry entry(String name, String... aliases) {
		return new Entry(UUID.nameUUIDFromBytes(name.getBytes()), name, List.of(aliases));
	}

	private static Optional<Match> match(String candidate, String... existing) {
		return IngredientNameMatcher.findMatch(candidate,
				java.util.Arrays.stream(existing).map(IngredientNameMatcherTest::entry).toList());
	}

	@Nested
	@DisplayName("split: the ingredient apart from its preparation (R-DUP-1)")
	class SplitRule {

		@ParameterizedTest(name = "{0} -> {1} + {2}")
		@CsvSource(delimiter = '|', value = {
				// The five the requirements name.
				"Cashew, halved              | Cashew             | halved",
				"Green chilli, slit          | Green chilli       | slit",
				"Ginger, paste               | Ginger             | paste",
				"Coconut, fresh grated       | Coconut            | fresh grated",
				"Curd, sour                  | Curd               | sour",
				// The rest of R-DUP-3's curd group.
				"Curd, fresh                 | Curd               | fresh",
				"Curd, whisked               | Curd               | whisked",
				// Provisional (Q-12): the cook's actions and states.
				"Onion, chopped fine         | Onion              | chopped fine",
				"Cucumber, grated and squeezed | Cucumber         | grated and squeezed",
				"Potato, diced small         | Potato             | diced small",
				"Groundnut, roasted and skinned | Groundnut       | roasted and skinned",
				"Rajma, soaked overnight     | Rajma              | soaked overnight",
				"Okra, cut and fried         | Okra               | cut and fried",
				"Potato, boiled and cubed    | Potato             | boiled and cubed",
				"Water, chilled              | Water              | chilled",
				"Water, boiling              | Water              | boiling",
				"Mustard seeds, coarsely ground | Mustard seeds   | coarsely ground",
				"Cardamom, ground            | Cardamom           | ground",
				// Several tails come off together, in the order they were written.
				"'Bamboo shoot, fresh, sliced' | Bamboo shoot     | 'fresh, sliced'",
				// Provisional (Q-12): a purpose becomes the note — this replaces the earlier plan to
				// keep "Tamarind, for the water" whole.
				"Tamarind, for the water     | Tamarind           | for the water",
				"Oil, for frying             | Oil                | for frying",
				"Ghee, for the dough         | Ghee               | for the dough",
				"Butter, for finishing       | Butter             | for finishing",
				"Buttermilk, to serve        | Buttermilk         | to serve",
				"Curd, as starter            | Curd               | as starter",
				"'Coconut, grated, for grinding' | Coconut        | 'grated, for grinding'",
				// Provisional (Q-12): a preparation at the front counts the same.
				"Grated coconut              | Coconut            | grated",
				"Sour curd                   | Curd               | sour",
				"Fresh grated coconut        | Coconut            | fresh grated",
				"'Grated coconut, fresh'     | Coconut            | 'grated, fresh'",
				"Chilled water               | Water              | chilled",
				// Without commas, at the end too — R-DUP-2's "Curd sour".
				"Curd sour                   | Curd               | sour",
				"Ginger paste                | Ginger             | paste",
				// Brackets travel with the head.
				"Wood apple (bela), grated   | Wood apple (bela)  | grated",
				// The first tail that is not a preparation stops it; what it stops at stays in the name.
				"'Rice, local, soaked'       | 'Rice, local'      | soaked",
				"'Groundnut oil, hot, for the dough' | 'Groundnut oil, hot' | for the dough",
				"'Semolina, fine, for the nagori' | 'Semolina, fine' | for the nagori",
		})
		void splitsOffThePreparation(String raw, String base, String note) {
			assertThat(IngredientNameMatcher.split(raw)).isEqualTo(new Split(base, note));
		}

		@ParameterizedTest(name = "{0} stays whole")
		@ValueSource(strings = {
				// Store-bought forms stay in the name (provisional Q-12 examples).
				"Rice, broken",
				"Salt, coarse",
				"Red chilli, dry",
				"Dates, seedless",
				"Coconut milk, thick",
				"Coconut, dry grated (kopra)",
				"Brinjal, small purple",
				"Tamarind, dried",
				"Curry leaves, dried",
				// "ground" is a preparation, except that black pepper is bought ground.
				"Black pepper, ground",
				"Black pepper ground",
				// Not ruled on, so left alone: a word nobody has decided is not moved.
				"Tomato, ripe",
				"Water, hot",
				"Cumin, roasted",
				"Rice, basmati",
				"Chilli, green",
				"Moong dal, split",
				"Onion, sliced thin",
				"Wood apple (bela), ripe",
				// A tempering mix is several ingredients, not one with a preparation.
				"Mustard, cumin, asafoetida",
				// Leading words that name a product: roasted gram is bought roasted.
				"Roasted gram",
				"Broken wheat",
				"Hot water",
				// Any other preposition, as IngredientNames: "with skin" is what is bought.
				"Urad dal, with skin",
				"Potato, in fingers",
				// Typos and edge cases: a doubled comma, a trailing comma, nothing before the comma.
				"Fennel and coriander seeds,,",
				"Ghee,",
				", chopped",
				// Taking the preparation off must leave an ingredient.
				"Paste",
				"Fresh",
				"Salt",
		})
		void leavesTheNameWhole(String raw) {
			assertThat(IngredientNameMatcher.split(raw)).isEqualTo(new Split(raw, null));
		}

		@Test
		@DisplayName("provisional, and a risk for Q-12: 'fresh' at the front also comes off")
		void freshAtTheFront() {
			// Fresh turmeric root and turmeric powder are stocked separately, but "fresh" is on the
			// provisional list and a front word counts the same. Pinned so that the consequence is
			// visible, and changes on purpose if Rajeev rules otherwise.
			assertThat(IngredientNameMatcher.split("Fresh turmeric")).isEqualTo(new Split("Turmeric", "fresh"));
		}

		@Test
		void tidiesSpacingAndNeverFailsOnNothing() {
			assertThat(IngredientNameMatcher.split("  Cashew ,  halved ")).isEqualTo(new Split("Cashew", "halved"));
			assertThat(IngredientNameMatcher.split(null)).isEqualTo(new Split("", null));
			assertThat(IngredientNameMatcher.split("   ")).isEqualTo(new Split("", null));
		}

		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {"Cashew, halved", "Grated coconut", "Curd sour", "Oil, for frying",
				"Rice, local, soaked", "Black pepper, ground", "Tomato, ripe"})
		@DisplayName("the base normalises the same as the whole name, so the three callers agree")
		void baseAndWholeNormaliseAlike(String raw) {
			assertThat(IngredientNameMatcher.normalise(IngredientNameMatcher.split(raw).base()))
					.isEqualTo(IngredientNameMatcher.normalise(raw));
		}
	}

	@Nested
	@DisplayName("normalise (R-DUP-2)")
	class Normalise {

		@ParameterizedTest(name = "{0} -> {1}")
		@CsvSource(delimiter = '|', value = {
				"Tomatos                 | tomato",
				"Tomatoes                | tomato",
				"tomato                  | tomato",
				"'  TOMATO  '            | tomato",
				"Curd, sour              | curd",
				"Curd sour               | curd",
				"Sour curd               | curd",
				"Tomato, ripe            | tomato ripe",
				"Tomato ripe             | tomato ripe",
				"Green chillies          | green chilli",
				"Curry leaves            | curry leaf",
				"Cloves                  | clove",
				"Peaches                 | peach",
				"Berries                 | berry",
				"Chickpeas, soaked       | chickpea",
				"Rava (coarse)           | rava coarse",
				"Tomato purée            | tomato puree",
				"Water-chestnut flour    | water chestnut flour",
				"Hibiscus                | hibiscus",
				"Dal                     | dal",
				"Black pepper, ground    | black pepper ground",
		})
		void normalises(String raw, String expected) {
			assertThat(IngredientNameMatcher.normalise(raw)).isEqualTo(expected);
		}

		@Test
		void nothingIsEmpty() {
			assertThat(IngredientNameMatcher.normalise(null)).isEmpty();
			assertThat(IngredientNameMatcher.normalise(" ,, ")).isEmpty();
		}
	}

	@Nested
	@DisplayName("findMatch")
	class FindMatch {

		@Test
		@DisplayName("AC R-DUP-2: 'Tomatos' is stopped when 'Tomato, ripe' exists")
		void tomatosMeetsTomatoRipe() {
			assertThat(match("Tomatos", "Tomato, ripe", "Onion"))
					.hasValueSatisfying(m -> {
						assertThat(m.entry().name()).isEqualTo("Tomato, ripe");
						assertThat(m.kind()).isEqualTo(MatchKind.CLOSE);
					});
		}

		@Test
		@DisplayName("R-DUP-2 example: 'Tomato ripe' against 'Tomato, ripe'")
		void tomatoRipeWithoutTheComma() {
			assertThat(match("Tomato ripe", "Tomato, ripe")).map(Match::kind).hasValue(MatchKind.EXACT);
		}

		@Test
		@DisplayName("AC R-DUP-2: 'Curd sour' is stopped when 'Curd' exists")
		void curdSourMeetsCurd() {
			assertThat(match("Curd sour", "Curd rice", "Curd")).hasValueSatisfying(m -> {
				assertThat(m.entry().name()).isEqualTo("Curd");
				assertThat(m.kind()).isEqualTo(MatchKind.EXACT);
			});
		}

		@Test
		@DisplayName("AC R-DUP-1: 'Green chilli, slit' finds 'Green chilli' exactly")
		void libraryLineFindsItsBase() {
			assertThat(match("Green chilli, slit", "Green chilli")).map(Match::kind).hasValue(MatchKind.EXACT);
		}

		@ParameterizedTest(name = "{0} = {1}")
		@CsvSource(delimiter = '|', value = {
				"Tomatoes          | Tomato",
				"tomato            | Tomatos",
				"Cashews           | Cashew, halved",
				"Grated coconut    | Coconut",
				"CURD.             | Curd",
				"Green chillies    | Green chilli",
		})
		void exact(String candidate, String existing) {
			assertThat(match(candidate, existing)).map(Match::kind).hasValue(MatchKind.EXACT);
		}

		@Test
		@DisplayName("an alias left by a merge finds the kept ingredient (R-DUP-3)")
		void throughAnAlias() {
			Entry curd = entry("Curd", "Dahi");
			Optional<Match> found = IngredientNameMatcher.findMatch("dahi", List.of(entry("Milk"), curd));
			assertThat(found).hasValueSatisfying(m -> {
				assertThat(m.entry()).isEqualTo(curd);
				assertThat(m.kind()).isEqualTo(MatchKind.EXACT);
				assertThat(m.matchedName()).isEqualTo("Dahi");
			});
		}

		@ParameterizedTest(name = "{0} ~ {1}")
		@CsvSource(delimiter = '|', value = {
				// One slip in a word of six letters or more.
				"Tamato            | Tomato",
				"Jaggary           | Jaggery",
				"Chili powder      | Chilli powder",
				"Tulasi leaves     | Tulsi leaf",
				"Panch phoron      | Panch phoran",
				// Spacing.
				"Greenchilli       | Green chilli",
				"Sweetcorn         | Sweet corn",
				// Bare against qualified: the requirement's own case, and its mirror.
				"Tomatos           | Tomato, ripe",
				"Rice, basmati     | Rice",
				"Rice              | Rice, basmati",
				"Salt              | Salt, coarse",
		})
		void close(String candidate, String existing) {
			assertThat(match(candidate, existing)).map(Match::kind).hasValue(MatchKind.CLOSE);
		}

		@ParameterizedTest(name = "{0} is not {1}")
		@CsvSource(delimiter = '|', value = {
				"Rice              | Rice flour",
				"Rice              | Raw rice",
				"Rice              | Curd rice",
				"Curd              | Curd rice",
				"Chana dal         | Toor dal",
				"Moong dal         | Urad dal",
				"Salt              | Rock salt",
				"Ghee              | Gheer",
				"Chana             | Chena",
				"Rava              | Ragi",
				"Maida             | Maize",
				"Ragi flour        | Rava flour",
				"Green chilli      | Red chilli",
				"Coconut oil       | Coconut milk",
				"Coconut           | Coconut oil",
				"Ginger            | Dry ginger",
				"Wheat             | Broken wheat",
				"Gram              | Roasted gram",
				"Black sesame      | White sesame",
				// Two varieties of the same thing do not match each other: neither is bare.
				"Rice, basmati     | Rice, sona masoori",
				"Moong dal, split  | Moong dal, whole",
				// Known gap: two edits apart, so not caught. The merge tool can fold it back.
				"Tur dal           | Toor dal",
		})
		void distinctIngredientsDoNotMatch(String candidate, String existing) {
			assertThat(match(candidate, existing)).isEmpty();
		}

		@ParameterizedTest(name = "{0} ~ {1} (known false match)")
		@CsvSource(delimiter = '|', value = {
				"Chana   | Chhana",
				"Butter  | Batter",
		})
		@DisplayName("known false matches: one edit in a six-letter word, pinned so a change shows")
		void knownFalseMatches(String candidate, String existing) {
			assertThat(match(candidate, existing)).map(Match::kind).hasValue(MatchKind.CLOSE);
		}

		@Test
		@DisplayName("EXACT beats CLOSE, and the shortest name wins a tie")
		void bestMatchWins() {
			assertThat(match("Curd", "Curd, thick", "Curd sour", "Curds"))
					.hasValueSatisfying(m -> {
						assertThat(m.kind()).isEqualTo(MatchKind.EXACT);
						assertThat(m.entry().name()).isEqualTo("Curds");
					});
			assertThat(match("Tomatos", "Tamato", "Tomato")).map(m -> m.entry().name()).hasValue("Tomato");
			assertThat(match("Tomato", "Tomato, ripe", "Tomatto")).map(m -> m.entry().name()).hasValue("Tomatto");
		}

		@Test
		void nothingToMatch() {
			assertThat(match("", "Curd")).isEmpty();
			assertThat(IngredientNameMatcher.findMatch("Curd", List.of())).isEmpty();
			assertThat(IngredientNameMatcher.findMatch(null, List.of(entry("Curd")))).isEmpty();
		}
	}
}
