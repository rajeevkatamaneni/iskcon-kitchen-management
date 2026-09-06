package org.iskcon.kms.ingredient;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The defect this exists for: "Hot water" translated to Kannada came back reading "Water Hot"
 * (Rajeev, 2026-09-04). Probing the real API showed the translator was faithful and the source was
 * inverted — the library files "Water, hot" so the three waters sort together.
 */
class IngredientNamesTest {

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
			"'Water, hot',                    'Hot water'",
			"'Water, cold',                   'Cold water'",
			"'Water, boiling',                'Boiling water'",
			"'Jaggery, grated',               'Grated jaggery'",
			"'Coconut, grated',               'Grated coconut'",
			"'Black pepper, crushed',         'Crushed black pepper'",
			"'Green chilli, slit',            'Slit green chilli'",
			"'Banana, ripe, mashed',          'Ripe mashed banana'",
			"'Wood apple (bela), ripe',       'Ripe wood apple (bela)'",
			"'Cottage cheese (chhena), crumbled', 'Crumbled cottage cheese (chhena)'",
	})
	@DisplayName("puts a filed name back into the order somebody says it")
	void unInverts(String filed, String spoken) {
		assertThat(IngredientNames.readable(filed)).isEqualTo(spoken);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			// A purpose, not an adjective. "For the water tamarind" is nonsense.
			"Tamarind, for the water",
			"Water, for the syrup",
			"Salt, for the water",
			"Water, for sprinkling",
			"Water, for boiling",
			"Water, for soaking",
			"Water, for leaching",
	})
	@DisplayName("leaves a purpose alone, because it is not an inversion")
	void keepsPurposeQualifiers(String name) {
		assertThat(IngredientNames.readable(name)).isEqualTo(name);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"Hot water", "Ghee", "Toor dal", "Water", "Water chestnut flour", "Rice water (kattu)",
	})
	@DisplayName("does not touch a name that was never inverted")
	void leavesPlainNames(String name) {
		assertThat(IngredientNames.readable(name)).isEqualTo(name);
	}

	@Test
	@DisplayName("lower-cases the head once it is no longer the first word, brand or not")
	void lowerCasesTheHead() {
		assertThat(IngredientNames.readable("Water, hot")).isEqualTo("Hot water");
		// A brand loses its capital too. Telling one from an ordinary noun needs a dictionary, and
		// this string is on its way to a machine translator, which does not read case.
		assertThat(IngredientNames.readable("Amul, unsalted")).isEqualTo("Unsalted amul");
	}

	@Test
	@DisplayName("survives the shapes that are not names at all")
	void survivesJunk() {
		assertThat(IngredientNames.readable(null)).isNull();
		assertThat(IngredientNames.readable("")).isEmpty();
		assertThat(IngredientNames.readable("  Ghee  ")).isEqualTo("Ghee");
		// A trailing or doubled comma is a typo, not an inversion; leave it be rather than guess.
		assertThat(IngredientNames.readable("Ghee,")).isEqualTo("Ghee,");
		assertThat(IngredientNames.readable(", hot")).isEqualTo(", hot");
	}
}
