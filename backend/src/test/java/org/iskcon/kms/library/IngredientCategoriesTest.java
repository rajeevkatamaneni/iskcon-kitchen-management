package org.iskcon.kms.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Which shelf the rules in {@link IngredientCategories} put a name on, measured over the real
 * catalogue rather than over examples chosen to pass.
 *
 * <p><strong>Why this test exists.</strong> {@code IngredientCategories} carried its coverage
 * figures in its javadoc — "88 of 99 distinct names, 423 of 454 lines" — and nothing in the build
 * checked them. A figure nobody re-measures is a figure that quietly stops being true the next time
 * the catalogue is rebuilt, and it is the figure the next person tuning these rules will trust. So
 * the measurement runs here, on the books in {@code src/main/resources/recipe-library}, and the
 * assertion is a floor: coverage may rise without anybody touching this file, and may not fall
 * without somebody deciding it should.
 *
 * <p><strong>What "covered" means.</strong> A name is covered when a rule names it — that is, when
 * {@link IngredientCategories#forName} returns anything other than {@code Other}. {@code Other} is a
 * real shelf, and water genuinely belongs on it, so a covered count is not a quality score; it is
 * the share of the catalogue a rule actually recognised.
 *
 * <p>Reads the books the way {@link LibraryLoader} does, from the classpath by pattern, so a book
 * added to the directory is measured without this test being edited.
 */
class IngredientCategoriesTest {

	private static final String BOOKS = "classpath:recipe-library/*.json";

	/** Every ingredient line in the catalogue, in the order the books write them. */
	private static List<String> lines() {
		ObjectMapper mapper = new ObjectMapper();
		List<String> names = new ArrayList<>();
		Resource[] books;
		try {
			books = new PathMatchingResourcePatternResolver().getResources(BOOKS);
		} catch (IOException e) {
			throw new IllegalStateException("Could not list the recipe books at " + BOOKS, e);
		}
		assertThat(books).as("recipe books on the classpath").isNotEmpty();
		for (Resource book : books) {
			try (InputStream in = book.getInputStream()) {
				JsonNode root = mapper.readTree(in);
				for (JsonNode recipe : root.path("recipes")) {
					for (JsonNode line : recipe.path("ing")) {
						names.add(line.path("name").asText());
					}
				}
			} catch (IOException e) {
				throw new IllegalStateException("Could not read " + book.getFilename(), e);
			}
		}
		return names;
	}

	@Nested
	@DisplayName("measured over the catalogue")
	class Measured {

		@Test
		@DisplayName("names all but a handful of the catalogue's lines, and says which it misses")
		void coverage() {
			List<String> lines = lines();
			Map<String, Integer> byName = new LinkedHashMap<>();
			for (String name : lines) {
				byName.merge(name, 1, Integer::sum);
			}

			Map<String, Integer> missed = new LinkedHashMap<>();
			int coveredLines = 0;
			int coveredNames = 0;
			for (Map.Entry<String, Integer> entry : byName.entrySet()) {
				if (IngredientCategories.FALLBACK.equals(IngredientCategories.forName(entry.getKey()))) {
					missed.put(entry.getKey(), entry.getValue());
				} else {
					coveredNames++;
					coveredLines += entry.getValue();
				}
			}

			// Printed, not only asserted: the next person tuning the rules wants the list of what is
			// still unnamed, and a failing floor alone would not give it to them.
			System.out.printf(
					"IngredientCategories over the catalogue: %d of %d lines (%.1f%%), %d of %d distinct names%n"
							+ "  not named by any rule: %s%n",
					coveredLines, lines.size(), 100.0 * coveredLines / lines.size(),
					coveredNames, byName.size(), missed);

			// A floor, not an equality. Rebuilding the catalogue changes the denominators, and a rule
			// that names more is an improvement nobody should have to come here to allow.
			assertThat(lines).hasSize(454);
			assertThat(byName).hasSize(99);
			assertThat(coveredLines)
					.as("catalogue lines a rule names, of %d", lines.size())
					.isGreaterThanOrEqualTo(430);
			assertThat(coveredNames)
					.as("distinct catalogue names a rule names, of %d", byName.size())
					.isGreaterThanOrEqualTo(92);
		}
	}

	@Nested
	@DisplayName("a plural reaches the rule that holds its singular")
	class Plurals {

		/**
		 * The four names the catalogue writes in the plural where the rules hold the singular. Before
		 * the categoriser singularised, all four fell to {@code Other} — seven lines — purely because
		 * of a trailing {@code s} sitting between the word and a {@code \b}.
		 *
		 * <p>They are listed here as evidence, not as the fix: the fix is the singular form of the
		 * whole name being tried, so the fifth plural nobody has met yet is right too. The test below
		 * proves that with names the catalogue does not contain.
		 */
		@Test
		@DisplayName("the four the catalogue actually writes")
		void inTheCatalogue() {
			assertThat(IngredientCategories.forName("Cloves")).isEqualTo("Spices");
			assertThat(IngredientCategories.forName("Coriander seeds")).isEqualTo("Spices");
			assertThat(IngredientCategories.forName("Lemons")).isEqualTo("Fruit");
			assertThat(IngredientCategories.forName("Raisins")).isEqualTo("Nuts & seeds");
		}

		@Test
		@DisplayName("and any other plural, including ones no book has written yet")
		void notInTheCatalogue() {
			// -s, -es, -ies and -oes, and a plural in a name of more than one word: the same
			// singularisation the duplicate-ingredient rule runs, so the two cannot drift apart.
			assertThat(IngredientCategories.forName("Cardamoms")).isEqualTo("Spices");
			assertThat(IngredientCategories.forName("Tomatoes")).isEqualTo("Vegetables");
			assertThat(IngredientCategories.forName("Green chillies")).isEqualTo("Spices");
			assertThat(IngredientCategories.forName("Cashews")).isEqualTo("Nuts & seeds");
			assertThat(IngredientCategories.forName("Guavas")).isEqualTo("Fruit");
		}

		@Test
		@DisplayName("a rule written in the plural still matches the plural the books write")
		void pluralsTheRulesAlreadyHeld() {
			// The singular pass runs only after the name as written has found nothing, so every one
			// of these takes the same path it always did. Pinned because singularising first —
			// the obvious implementation — breaks all of them: "beans" would become "bean",
			// "greens" "green", "dates" "date", and no rule holds those.
			assertThat(IngredientCategories.forName("Beans")).isEqualTo("Vegetables");
			assertThat(IngredientCategories.forName("Cluster beans")).isEqualTo("Vegetables");
			assertThat(IngredientCategories.forName("Amaranth greens")).isEqualTo("Vegetables");
			assertThat(IngredientCategories.forName("Curry leaves")).isEqualTo("Vegetables");
			assertThat(IngredientCategories.forName("Coriander leaves")).isEqualTo("Vegetables");
			assertThat(IngredientCategories.forName("Dates")).isEqualTo("Fruit");
			assertThat(IngredientCategories.forName("Mustard seeds")).isEqualTo("Spices");
			assertThat(IngredientCategories.forName("Peas")).isEqualTo("Vegetables");
		}

		@Test
		@DisplayName("water and the temple's own blends are still Other, and that is correct")
		void stillOther() {
			// Not a gap to be closed. Water is on the Other shelf by rule, and a temple's own spice
			// blend is a thing only that temple can file.
			assertThat(IngredientCategories.forName("Water")).isEqualTo(IngredientCategories.FALLBACK);
			assertThat(IngredientCategories.forName("Bisi bele bath pudi"))
					.isEqualTo(IngredientCategories.FALLBACK);
			assertThat(IngredientCategories.forName("Mixed vegetables"))
					.isEqualTo(IngredientCategories.FALLBACK);
		}
	}
}
