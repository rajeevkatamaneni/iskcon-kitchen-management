package org.iskcon.kms.translation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two halves of a string on its way into another language, and why they differ.
 *
 * <p>{@code IngredientNamesTest} already proves {@code readable()} un-inverts a filed name. What it
 * could not prove — and what let the defect survive its own fix for a fortnight — is that the
 * un-inversion is actually <em>reached</em> on every path that translates a name. That is what
 * {@code IngredientNameIsUnInvertedOnEveryPathTest} asserts; this class covers the decision itself.
 */
class TranslatableTest {

	@Test
	@DisplayName("a filed ingredient name reaches the machine un-inverted")
	void nameIsUnInverted() {
		Translatable name = Translatable.ingredientName("Water, hot");
		assertThat(name.forMachine()).isEqualTo("Hot water");
	}

	@Test
	@DisplayName("but the glossary is looked up on the name as the temple filed it")
	void glossaryKeepsTheFiledName() {
		Translatable name = Translatable.ingredientName("Water, hot");
		assertThat(name.glossaryKey()).isEqualTo("Water, hot");
		// A temple that wrote its own word against the filed name still gets it. Looking the
		// glossary up on "Hot water" would miss every entry anybody has ever typed.
		assertThat(name.override(Map.of("water, hot", "ಬಿಸಿ ನೀರು"))).isEqualTo("ಬಿಸಿ ನೀರು");
	}

	@Test
	@DisplayName("free text has no inversion to undo, so both halves are the same")
	void textIsUntouched() {
		Translatable purpose = Translatable.text("For Sunday feast, extra rice");
		assertThat(purpose.forMachine()).isEqualTo("For Sunday feast, extra rice");
		assertThat(purpose.glossaryKey()).isEqualTo("For Sunday feast, extra rice");
	}

	@Test
	@DisplayName("no glossary entry means the machine gets it")
	void noOverride() {
		assertThat(Translatable.ingredientName("Ghee").override(Map.of())).isNull();
	}

	@Test
	@DisplayName("the glossary lookup does not depend on the default locale")
	void lowercasesWithRootLocale() {
		// In a Turkish locale "I" lower-cases to a dotless "ı" and the lookup silently stops
		// matching. Two of the three call sites this replaced used the default locale.
		java.util.Locale was = java.util.Locale.getDefault();
		try {
			java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
			Translatable name = Translatable.ingredientName("Idli rice");
			assertThat(name.override(Map.of("idli rice", "ಇಡ್ಲಿ ಅಕ್ಕಿ"))).isEqualTo("ಇಡ್ಲಿ ಅಕ್ಕಿ");
		} finally {
			java.util.Locale.setDefault(was);
		}
	}
}
