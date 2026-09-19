package org.iskcon.kms.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Rajeev's answer to Q-11 (2026-09-19, T-287): when a library recipe names an ingredient that is
 * only <em>close</em> to one the temple has, the copy asks — "Use Tomato, ripe" or "It's a different
 * ingredient" — instead of deciding for them. docs/work/PROCUREMENT-REQUIREMENTS.md R-DUP-1, R-DUP-2.
 *
 * <p>What is pinned here: the list the screen asks about ({@code GET …/close-matches}); a copy with a
 * close match left unanswered refused with KMS-400156 and nothing written; "Use" putting the line on
 * the existing ingredient with its note; "different" creating it with the same audit entry the
 * ingredient form writes for the same override; exact matches still silent; and every malformed
 * answer refused as a field error, again with nothing written.
 *
 * <p>Through the HTTP endpoints as the temple's own admin, so RLS is in the path: the catalogue the
 * matcher reads is the one the database lets this temple see.
 */
@AutoConfigureMockMvc
class RecipeImportCloseMatchIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID templeB;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
		insertUser(templeA, "uid-admin-a", "TEMPLE_ADMIN", "+919876500081");
		stubVerifier.accept("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM ingredient_aliases");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM master_recipes");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("the screen's list: \"Tomatos\" is a close match for Tomato, ripe, with its note; the exact Green chilli is not listed")
	void closeMatchesListsOnlyTheCloseOnes() throws Exception {
		UUID tomato = insertIngredient(templeA, "Tomato, ripe");
		insertIngredient(templeA, "Green chilli");
		UUID master = saaru();

		mvc.perform(authed(get("/api/v1/recipes/import/{id}/close-matches", master)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].libraryName").value("Tomatos"))
				.andExpect(jsonPath("$[0].note").value("chopped"))
				.andExpect(jsonPath("$[0].existingIngredientId").value(tomato.toString()))
				.andExpect(jsonPath("$[0].existingIngredientName").value("Tomato, ripe"));

		assertNothingWritten(2);
	}

	@Test
	@DisplayName("AC: copying with \"Tomatos\" when \"Tomato, ripe\" exists and no answer is refused with KMS-400156, listing the close match, and writes nothing")
	void noAnswerIsRefusedAndWritesNothing() throws Exception {
		UUID tomato = insertIngredient(templeA, "Tomato, ripe");
		insertIngredient(templeA, "Green chilli");
		UUID master = saaru();

		mvc.perform(authed(post("/api/v1/recipes/import/{id}", master)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400156"))
				.andExpect(jsonPath("$.fieldErrors.length()").value(4))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("closeMatches[0].libraryName"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("Tomatos"))
				.andExpect(jsonPath("$.fieldErrors[1].field").value("closeMatches[0].note"))
				.andExpect(jsonPath("$.fieldErrors[1].message").value("chopped"))
				.andExpect(jsonPath("$.fieldErrors[2].field").value("closeMatches[0].existingIngredientId"))
				.andExpect(jsonPath("$.fieldErrors[2].message").value(tomato.toString()))
				.andExpect(jsonPath("$.fieldErrors[3].field").value("closeMatches[0].existingIngredientName"))
				.andExpect(jsonPath("$.fieldErrors[3].message").value("Tomato, ripe"));

		assertNothingWritten(2);
	}

	@Test
	@DisplayName("\"Use Tomato, ripe\": no ingredient is created, and the line goes on Tomato, ripe with its note kept")
	void useMapsTheLineToTheExistingIngredient() throws Exception {
		UUID tomato = insertIngredient(templeA, "Tomato, ripe");
		UUID chilli = insertIngredient(templeA, "Green chilli");
		UUID master = saaru();

		String body = importWith(master, use("Tomatos", tomato)).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String recipeId = JsonPath.read(body, "$.id");

		assertThat(JsonPath.<Integer>read(body, "$.ingredientsCreated")).isZero();
		assertThat(ingredientNames(templeA)).containsExactly("Green chilli", "Tomato, ripe");
		assertThat(lineIngredients(recipeId)).containsExactly(tomato, chilli);
		assertThat(notes(recipeId)).containsExactly("chopped", "slit");
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'INGREDIENT_ADDED'", Integer.class)).isZero();
		// The import's own entry says the line was put there by choice.
		assertThat(admin.queryForObject(
				"SELECT after_state->'closeMatches'->0->>'answer' FROM audit_events WHERE action = 'RECIPE_IMPORTED'",
				String.class)).isEqualTo("USED_EXISTING");
	}

	@Test
	@DisplayName("\"It's a different ingredient\", confirmed: Tomatos is created, and the override is audited as the ingredient form audits it")
	void differentCreatesAndAudits() throws Exception {
		UUID tomato = insertIngredient(templeA, "Tomato, ripe");
		insertIngredient(templeA, "Green chilli");
		UUID master = saaru();

		String body = importWith(master, different("Tomatos")).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String recipeId = JsonPath.read(body, "$.id");

		assertThat(JsonPath.<Integer>read(body, "$.ingredientsCreated")).isEqualTo(1);
		assertThat(ingredientNames(templeA)).containsExactly("Green chilli", "Tomato, ripe", "Tomatos");
		UUID tomatos = admin.queryForObject(
				"SELECT id FROM ingredients WHERE tenant_id = ? AND name = 'Tomatos'", UUID.class, templeA);
		assertThat(admin.queryForObject(
				"SELECT library_derived FROM ingredients WHERE id = ?", Boolean.class, tomatos)).isTrue();
		assertThat(lineIngredients(recipeId).get(0)).isEqualTo(tomatos);
		assertThat(notes(recipeId)).containsExactly("chopped", "slit");

		// The same shape as IngredientService's override (DuplicateIngredientIT): the reason names
		// what it looked like, and after_state carries confirmedDifferentFrom.
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'INGREDIENT_ADDED'", Integer.class)).isEqualTo(1);
		assertThat(admin.queryForObject(
				"SELECT entity_id FROM audit_events WHERE action = 'INGREDIENT_ADDED'", UUID.class)).isEqualTo(tomatos);
		assertThat(admin.queryForObject(
				"SELECT reason FROM audit_events WHERE action = 'INGREDIENT_ADDED'", String.class))
				.isEqualTo("Added although it looks like “Tomato, ripe”: confirmed as a different ingredient.");
		assertThat(admin.queryForObject(
				"SELECT after_state->'confirmedDifferentFrom'->>'id' FROM audit_events WHERE action = 'INGREDIENT_ADDED'",
				String.class)).isEqualTo(tomato.toString());
		assertThat(admin.queryForObject(
				"SELECT after_state->'confirmedDifferentFrom'->>'name' FROM audit_events WHERE action = 'INGREDIENT_ADDED'",
				String.class)).isEqualTo("Tomato, ripe");
		assertThat(admin.queryForObject(
				"SELECT after_state->'confirmedDifferentFrom'->>'kind' FROM audit_events WHERE action = 'INGREDIENT_ADDED'",
				String.class)).isEqualTo("CLOSE");
		assertThat(admin.queryForObject(
				"SELECT tenant_id FROM audit_events WHERE action = 'INGREDIENT_ADDED'", UUID.class)).isEqualTo(templeA);
		assertThat(admin.queryForObject(
				"SELECT after_state->'closeMatches'->0->>'answer' FROM audit_events WHERE action = 'RECIPE_IMPORTED'",
				String.class)).isEqualTo("DIFFERENT_INGREDIENT");
	}

	@Test
	@DisplayName("\"different\" without confirmDifferent is no answer: still KMS-400156, nothing written")
	void differentWithoutConfirmationIsNoAnswer() throws Exception {
		insertIngredient(templeA, "Tomato, ripe");
		insertIngredient(templeA, "Green chilli");
		UUID master = saaru();

		importWith(master, "{\"libraryName\":\"Tomatos\",\"useIngredientId\":null,\"confirmDifferent\":false}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400156"));

		assertNothingWritten(2);
	}

	@Test
	@DisplayName("an exact match is mapped silently, as before: nothing to answer, no body needed, nothing created")
	void exactMatchIsSilent() throws Exception {
		UUID tomato = insertIngredient(templeA, "Tomato");
		UUID chilli = insertIngredient(templeA, "Green chilli");
		UUID master = saaru(); // "Tomatos, chopped" normalises to "tomato": EXACT

		mvc.perform(authed(get("/api/v1/recipes/import/{id}/close-matches", master)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));

		String body = mvc.perform(authed(post("/api/v1/recipes/import/{id}", master)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String recipeId = JsonPath.read(body, "$.id");

		assertThat(ingredientNames(templeA)).containsExactly("Green chilli", "Tomato");
		assertThat(lineIngredients(recipeId)).containsExactly(tomato, chilli);
		assertThat(notes(recipeId)).containsExactly("chopped", "slit");
		assertThat(admin.queryForObject(
				"SELECT jsonb_exists(after_state, 'closeMatches') FROM audit_events WHERE action = 'RECIPE_IMPORTED'",
				Boolean.class)).isFalse();
	}

	@Test
	@DisplayName("answers are validated: an unknown name, a different ingredient's id, both answers at once and a repeat are field errors, and write nothing")
	void answersAreValidated() throws Exception {
		UUID tomato = insertIngredient(templeA, "Tomato, ripe");
		UUID chilli = insertIngredient(templeA, "Green chilli");
		UUID master = saaru();

		importWith(master, different("Brinjal"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("decisions[0].libraryName"))
				.andExpect(jsonPath("$.fieldErrors[0].message")
						.value("This recipe has no ingredient called “Brinjal” that needs an answer."));

		// Green chilli is the temple's, but it is not what "Tomatos" was matched with.
		importWith(master, use("Tomatos", chilli))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("decisions[0].useIngredientId"));

		importWith(master, "{\"libraryName\":\"Tomatos\",\"useIngredientId\":\"" + tomato
				+ "\",\"confirmDifferent\":true}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors[0].field").value("decisions[0].confirmDifferent"));

		// Compared as the matcher compares, so "tomatoes" is a repeat of "Tomatos".
		importWith(master, use("Tomatos", tomato), different("tomatoes"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors[0].field").value("decisions[1].libraryName"));

		importWith(master, "{\"libraryName\":\"  \",\"useIngredientId\":null,\"confirmDifferent\":true}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors[0].field").value("decisions[0].libraryName"));

		assertNothingWritten(2);
	}

	@Test
	@DisplayName("two lines naming the same thing get one question, and \"different\" makes one ingredient for both")
	void oneQuestionPerName() throws Exception {
		insertIngredient(templeA, "Tomato, ripe");
		UUID master = insertLibraryRecipe("Tomato Rasam",
				line("Tomatos, chopped", "300 gm", "300", "GM"),
				line("Tomatos", "100 gm", "100", "GM"));

		mvc.perform(authed(get("/api/v1/recipes/import/{id}/close-matches", master)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].libraryName").value("Tomatos"))
				.andExpect(jsonPath("$[0].note").value("chopped"));

		String body = importWith(master, different("Tomatos")).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String recipeId = JsonPath.read(body, "$.id");

		assertThat(JsonPath.<Integer>read(body, "$.ingredientsCreated")).isEqualTo(1);
		List<UUID> lines = lineIngredients(recipeId);
		assertThat(lines.get(0)).isEqualTo(lines.get(1));
		assertThat(notes(recipeId)).containsExactly("chopped", null);
	}

	@Test
	@DisplayName("RLS: another temple's Tomato, ripe is never a close match")
	void anotherTemplesIngredientIsNotAskedAbout() throws Exception {
		insertIngredient(templeB, "Tomato, ripe");
		UUID master = saaru();

		mvc.perform(authed(get("/api/v1/recipes/import/{id}/close-matches", master)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
		mvc.perform(authed(post("/api/v1/recipes/import/{id}", master)))
				.andExpect(status().isCreated());

		assertThat(ingredientNames(templeA)).containsExactly("Green chilli", "Tomatos");
		assertThat(ingredientNames(templeB)).containsExactly("Tomato, ripe");
	}

	@Test
	@DisplayName("the list is behind the copy's own permission: a volunteer is refused both")
	void volunteerIsRefused() throws Exception {
		insertUser(templeA, "uid-volunteer-a", "VOLUNTEER", "+919876500082");
		stubVerifier.accept("uid-volunteer-a");
		UUID master = saaru();

		mvc.perform(get("/api/v1/recipes/import/{id}/close-matches", master)
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/recipes/import/{id}", master)
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isForbidden());
	}

	// ------------------------------------------------ A-N5: the rest of the typed name (T-297)

	/**
	 * The recipes VERIFY2-A found losing words (A-N5): each name is only a close match for the
	 * temple's bare ingredient, because the qualifier is not on the preparation-word list (held,
	 * Q-12), so the split leaves it whole. Oldest first, in the order the catalogue is inserted.
	 */
	private static final String[][] N5 = {
			{"Ginger, peeled", "Ginger", "peeled"},
			{"Mustard, split", "Mustard", "split"},
			{"Curd, thick", "Curd", "thick"},
			{"Semolina, fine", "Semolina", "fine"},
			{"Jaggery, dark", "Jaggery", "dark"},
			{"Cashew, broken", "Cashew", "broken"},
			{"Sugar, powdered", "Sugar", "powdered"},
			{"Ghee, hot", "Ghee", "hot"},
	};

	@Test
	@DisplayName("A-N5: the screen's list shows each line's note before it is answered — \"Ginger, peeled\" with Ginger carries \"peeled\", and so do the other seven")
	void theListCarriesTheRestOfTheNameAsTheNote() throws Exception {
		List<UUID> ids = new java.util.ArrayList<>();
		for (String[] n : N5) {
			ids.add(insertIngredient(templeA, n[1]));
		}
		UUID master = n5Recipe();

		ResultActions list = mvc.perform(authed(get("/api/v1/recipes/import/{id}/close-matches", master)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(N5.length));
		for (int i = 0; i < N5.length; i++) {
			list.andExpect(jsonPath("$[" + i + "].libraryName").value(N5[i][0]))
					.andExpect(jsonPath("$[" + i + "].note").value(N5[i][2]))
					.andExpect(jsonPath("$[" + i + "].existingIngredientId").value(ids.get(i).toString()))
					.andExpect(jsonPath("$[" + i + "].existingIngredientName").value(N5[i][1]));
		}

		// The refusal a screen that did not ask first gets carries the same note.
		mvc.perform(authed(post("/api/v1/recipes/import/{id}", master)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400156"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("Ginger, peeled"))
				.andExpect(jsonPath("$.fieldErrors[1].field").value("closeMatches[0].note"))
				.andExpect(jsonPath("$.fieldErrors[1].message").value("peeled"));

		assertNothingWritten(N5.length);
	}

	@Test
	@DisplayName("A-N5: \"Use Ginger\" for \"Ginger, peeled\" saves Ginger · peeled; all eight lines keep their word, and nothing is created")
	void useKeepsTheRestOfTheNameOnTheLine() throws Exception {
		List<UUID> ids = new java.util.ArrayList<>();
		for (String[] n : N5) {
			ids.add(insertIngredient(templeA, n[1]));
		}
		UUID master = n5Recipe();

		String[] answers = new String[N5.length];
		for (int i = 0; i < N5.length; i++) {
			answers[i] = use(N5[i][0], ids.get(i));
		}
		String body = importWith(master, answers).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String recipeId = JsonPath.read(body, "$.id");

		assertThat(JsonPath.<Integer>read(body, "$.ingredientsCreated")).isZero();
		assertThat(lineIngredients(recipeId)).containsExactlyElementsOf(ids);
		assertThat(notes(recipeId)).containsExactly(
				"peeled", "split", "thick", "fine", "dark", "broken", "powdered", "hot");
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM ingredients WHERE tenant_id = ?", Integer.class, templeA)).isEqualTo(N5.length);
	}

	@Test
	@DisplayName("A-N5: \"It's a different ingredient\" still creates \"Ginger, peeled\" whole, so its line has no note — the words are in the name")
	void differentKeepsTheWholeNameAndNoNote() throws Exception {
		insertIngredient(templeA, "Ginger");
		UUID master = insertLibraryRecipe("Allam Pachadi", line("Ginger, peeled", "100 gm", "100", "GM"));

		String body = importWith(master, different("Ginger, peeled")).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String recipeId = JsonPath.read(body, "$.id");

		assertThat(ingredientNames(templeA)).containsExactly("Ginger", "Ginger, peeled");
		assertThat(notes(recipeId)).containsExactly((String) null);
	}

	@Test
	@DisplayName("A-N5: a word-list note and a left-over word together read in the library's order — \"Rice, basmati, soaked\" with Rice is Rice · basmati, soaked")
	void wordListNoteAndLeftOverTogether() throws Exception {
		UUID rice = insertIngredient(templeA, "Rice");
		UUID master = insertLibraryRecipe("Basmati Pulao", line("Rice, basmati, soaked", "1 Kg", "1", "KG"));

		mvc.perform(authed(get("/api/v1/recipes/import/{id}/close-matches", master)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].libraryName").value("Rice, basmati"))
				.andExpect(jsonPath("$[0].note").value("basmati, soaked"));

		String body = importWith(master, use("Rice, basmati", rice)).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		assertThat(notes(JsonPath.read(body, "$.id"))).containsExactly("basmati, soaked");
	}

	@Test
	@DisplayName("A-N5 edge cases of the left-over rule, read directly: spellings leave nothing, a qualifier after a comma is kept as written, words only at an end")
	void leftOverRule() {
		UUID id = UUID.randomUUID();
		java.util.function.BiFunction<String, String, String> left = (typed, chosen) ->
				RecipeImportService.leftOver(typed, new org.iskcon.kms.ingredient.IngredientNameMatcher.Match(
						new org.iskcon.kms.ingredient.IngredientNameMatcher.Entry(id, chosen),
						org.iskcon.kms.ingredient.IngredientNameMatcher.MatchKind.CLOSE, chosen));

		assertThat(left.apply("Ginger, peeled", "Ginger")).isEqualTo("peeled");
		assertThat(left.apply("Gingers, peeled", "Ginger")).isEqualTo("peeled");         // plural head
		assertThat(left.apply("Jaggary, dark", "Jaggery")).isEqualTo("dark");            // one-letter slip in the head
		assertThat(left.apply("Rice, basmati, aged", "Rice")).isEqualTo("basmati, aged");
		assertThat(left.apply("Curd, Thick", "Curd")).isEqualTo("Thick");               // a comma tail is kept as written
		assertThat(left.apply("Thick curd", "Curd")).isEqualTo("thick");                // front word, lower case
		assertThat(left.apply("Curd thick", "Curd")).isEqualTo("thick");                // end word
		assertThat(left.apply("Hot curd rice", "Curd")).isNull();                       // only an end, never the middle
		assertThat(left.apply("Tomatos", "Tomato, ripe")).isNull();                     // the bare side: nothing left
		assertThat(left.apply("Tomatos", "Tomato")).isNull();                           // a spelling
		assertThat(left.apply("Greenchilli", "Green chilli")).isNull();                 // a spacing
		assertThat(left.apply("Tomato, ripe", "Tomato, red")).isNull();                 // two varieties: not taken apart
		assertThat(left.apply("Ginger, peeled", "Garlic")).isNull();                    // not the chosen name at all

		// Order, when the word list found a note as well.
		assertThat(RecipeImportService.combineNotes("Rice, basmati, soaked", "soaked", "basmati"))
				.isEqualTo("basmati, soaked");
		assertThat(RecipeImportService.combineNotes("Chopped tomato, ripe", "chopped", "ripe"))
				.isEqualTo("chopped, ripe");
		assertThat(RecipeImportService.combineNotes("Tomatos, chopped", "chopped", null)).isEqualTo("chopped");
		assertThat(RecipeImportService.combineNotes("Ginger, peeled", null, "peeled")).isEqualTo("peeled");
	}

	/** Allam Uragaya's two lines and the six other names VERIFY2-A listed, in one recipe. */
	private UUID n5Recipe() {
		Line[] lines = new Line[N5.length];
		for (int i = 0; i < N5.length; i++) {
			lines[i] = line(N5[i][0], "50 gm", "50", "GM");
		}
		return insertLibraryRecipe("Allam Uragaya", lines);
	}

	// ---------------------------------------------------------------------

	/** "Tomatos, chopped" (close to "Tomato, ripe", exact to "Tomato") and "Green chilli, slit". */
	private UUID saaru() {
		return insertLibraryRecipe("Tomato Saaru",
				line("Tomatos, chopped", "300 gm", "300", "GM"),
				line("Green chilli, slit", "20 gm", "20", "GM"));
	}

	private static String use(String libraryName, UUID id) {
		return "{\"libraryName\":\"" + libraryName + "\",\"useIngredientId\":\"" + id + "\",\"confirmDifferent\":false}";
	}

	private static String different(String libraryName) {
		return "{\"libraryName\":\"" + libraryName + "\",\"useIngredientId\":null,\"confirmDifferent\":true}";
	}

	private ResultActions importWith(UUID master, String... decisions) throws Exception {
		return mvc.perform(authed(post("/api/v1/recipes/import/{id}", master))
				.contentType("application/json")
				.content("{\"decisions\":[" + String.join(",", decisions) + "]}"));
	}

	/** No recipe, no category, no new ingredient, no audit entry. */
	private void assertNothingWritten(int ingredientsBefore) {
		assertThat(admin.queryForObject("SELECT count(*) FROM recipes", Integer.class)).isZero();
		assertThat(admin.queryForObject("SELECT count(*) FROM recipe_ingredients", Integer.class)).isZero();
		assertThat(admin.queryForObject("SELECT count(*) FROM recipe_categories", Integer.class)).isZero();
		assertThat(admin.queryForObject("SELECT count(*) FROM audit_events", Integer.class)).isZero();
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM ingredients WHERE tenant_id = ?", Integer.class, templeA))
				.isEqualTo(ingredientsBefore);
	}

	private record Line(String name, String qty, String qtyValue, String qtyUnit) {
	}

	private static Line line(String name, String qty, String qtyValue, String qtyUnit) {
		return new Line(name, qty, qtyValue, qtyUnit);
	}

	private UUID insertLibraryRecipe(String name, Line... lines) {
		StringBuilder json = new StringBuilder("[");
		StringBuilder names = new StringBuilder("{");
		for (int i = 0; i < lines.length; i++) {
			if (i > 0) {
				json.append(',');
				names.append(',');
			}
			json.append("{\"name\":\"").append(lines[i].name()).append("\",\"qty\":\"").append(lines[i].qty())
					.append("\",\"qtyValue\":").append(lines[i].qtyValue())
					.append(",\"qtyUnit\":\"").append(lines[i].qtyUnit()).append("\"}");
			names.append('"').append(lines[i].name()).append('"');
		}
		json.append(']');
		names.append('}');
		String slug = name.toLowerCase().replace(' ', '-');
		return admin.queryForObject("""
				INSERT INTO master_recipes (state_slug, state, book_language, recipe_slug, name,
					display_name, category_key, category_name, badge, yield_text, yield_qty,
					yield_unit, why, ingredient_names, ingredients, method, source_ref)
				VALUES ('karnataka', 'Karnataka', 'English', ?, ?, ?, 'beverages', 'Beverages', 'Everyday',
					'10 L', 10, 'L', 'because', CAST(? AS text[]), CAST(? AS jsonb), '[\"Mix.\"]'::jsonb, 'test')
				RETURNING id
				""", UUID.class, slug, name, name, names.toString(), json.toString());
	}

	private List<String> notes(String recipeId) {
		return admin.queryForList(
				"SELECT preparation_note FROM recipe_ingredients WHERE recipe_id = ?::uuid ORDER BY line_order",
				String.class, recipeId);
	}

	private List<UUID> lineIngredients(String recipeId) {
		return admin.queryForList(
				"SELECT ingredient_id FROM recipe_ingredients WHERE recipe_id = ?::uuid ORDER BY line_order",
				UUID.class, recipeId);
	}

	private List<String> ingredientNames(UUID tenant) {
		return admin.queryForList(
				"SELECT name FROM ingredients WHERE tenant_id = ? ORDER BY name", String.class, tenant);
	}

	private UUID insertIngredient(UUID tenant, String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Test', 'GM') RETURNING id
				""", UUID.class, tenant, name);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private UUID insertTenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, name);
	}

	private void insertUser(UUID tenantId, String uid, String role, String phone) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, ?, ?, 'ACTIVE')
				""", tenantId, uid, uid + "@example.com", phone, role);
	}
}
