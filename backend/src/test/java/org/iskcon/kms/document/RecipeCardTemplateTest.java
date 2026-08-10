package org.iskcon.kms.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The recipe card HTML (E2-S5): content present, and user text escaped. */
class RecipeCardTemplateTest {

	@Test
	@DisplayName("renders the temple, recipe, ingredients and method, and escapes user text")
	void rendersAndEscapes() {
		RecipeCardTemplate.CardModel model = new RecipeCardTemplate.CardModel(
				"ISKCON Bangalore",
				"Khichdi <spicy>",
				"Rice",
				"Yields 100 servings",
				null,
				List.of(new RecipeCardTemplate.Row("Rice", "2 Kg", false),
						new RecipeCardTemplate.Row("Toor Dal", "1 Kg", false)),
				List.of("Wash the rice.", "Cook together until soft."),
				"The default temple lunch.",
				"10 Aug 2026");

		String html = RecipeCardTemplate.render(model);

		assertThat(html).contains("ISKCON Bangalore").contains("Rice").contains("Toor Dal")
				.contains("2 Kg").contains("Wash the rice.").contains("10 Aug 2026");
		assertThat(html)
				.as("user-entered text must be HTML-escaped")
				.contains("Khichdi &lt;spicy&gt;")
				.doesNotContain("Khichdi <spicy>");
		assertThat(html).startsWith("<!doctype html>");
	}

	@Test
	@DisplayName("shows the sattvic override badge when a reason is present")
	void showsOverrideBadge() {
		RecipeCardTemplate.CardModel model = new RecipeCardTemplate.CardModel(
				"Temple", "Garlic Rice", "Rice", "Yields 100 servings",
				"Approved by temple head", List.of(), List.of(), null, "10 Aug 2026");

		assertThat(RecipeCardTemplate.render(model)).contains("Sattvic override: Approved by temple head");
	}
}
