package org.iskcon.kms.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.meal.MealFixture;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The shared recipe library, end to end (E2-S9, E2-S10, E2-S12, E2-S15).
 *
 * <p>Loaded from the <em>real</em> books in {@code src/main/resources/recipe-library} rather than a
 * fixture. A fixture would prove the loader can read a file somebody wrote to make the test pass;
 * the risk being carried here is the recipes a person wrote to be cooked, and those are the ones
 * that have to go in.
 *
 * <h2>What changed on 2026-09-19, and why almost every number in this class moved</h2>
 *
 * <p>Until that day the directory held 32 state books vendored from {@code kranthimj23/ikms} —
 * 5,376 recipes, 168 per book, none of them vetted. Rajeev stopped that: <em>"It was a BAD idea to
 * mass import that many recipes without vetting each first."</em> What is there now is his curated
 * catalogue: <strong>44 recipes he approved by hand</strong>, generated from
 * {@code docs/work/reference/curated-recipes/} by {@code tools/seed/02b-build-catalogue.mjs} into
 * two books — Karnataka (42) and Andhra Pradesh (2).
 *
 * <p>So the assertions here are no longer "a big import arrived intact". They are "the 44 dishes the
 * temple actually cooks are all present, all parsed, and all carry the two things his curation adds
 * that no vendored book ever had — the preparation of a line, and the mark on a line the temple
 * never buys". Every figure below was counted out of the JSON, not estimated.
 *
 * <p>Two consequences worth stating, because they read as missing coverage otherwise. His 44 names
 * are all distinct, so the disambiguation ladder has nothing to disambiguate and rungs 1 and 2 are
 * unreachable from the real files — {@link #ladderSuffixesOnlyWhenNamesCollide()} drives them from a
 * fixture instead. And none of his recipes carries a {@code tags} array, so the old "a tag is
 * searchable" case is gone; the category name standing in for it is asserted in {@link #search()}.
 */
@AutoConfigureMockMvc
class RecipeLibraryIT extends AbstractIntegrationTest {

	/** Rajeev's curated catalogue: 42 Karnataka + 2 Andhra Pradesh, counted from the two JSON files. */
	private static final int EXPECTED_RECIPES = 44;

	/** Two books, down from 32: every other state book was deleted unvetted on 2026-09-19. */
	private static final int EXPECTED_BOOKS = 2;

	/** Ingredient lines across all 44 recipes. */
	private static final int EXPECTED_INGREDIENT_LINES = 454;

	/** Lines carrying a preparation of their own. 26 distinct ones, "Grated" 31 times of the 83. */
	private static final int EXPECTED_PREPARATIONS = 83;

	/** Lines marked as something the temple never buys. Every one of them is water. */
	private static final int EXPECTED_NOT_BOUGHT = 15;

	/**
	 * The recipe the import tests are driven through, and why this one.
	 *
	 * <p>It was Majjige until the curation, which does not include it. Chitranna is the nearest
	 * equivalent: an everyday dish, filed under a category every temple is seeded with, yielding in
	 * litres with a stated portion, and with a long enough ingredient list that a line lost on the
	 * way in would show up in a count.
	 */
	private static final String IMPORTED = "Chitranna";

	/** Chitranna's ingredient lines, all 14 of them distinct names. */
	private static final int IMPORTED_LINES = 14;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private LibraryLoader loader;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID templeB;

	@BeforeAll
	static void quiet() {
		// Nothing to do; kept as the place to say that this class loads the whole library once per
		// test method on purpose — the load is the thing under test, and it takes about a second.
	}

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
		insertUser(templeA, "uid-admin-a", "TEMPLE_ADMIN");
		insertUser(templeB, "uid-admin-b", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-cook-a", "KITCHEN_STAFF");
		insertOperator("uid-operator");
		seedCategories(templeA);
		seedCategories(templeB);
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM recipe_ingredients");
		MealFixture.deleteAll(admin);
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		// Anything that moved through the stock ledger is tracked now, so the item rows exist
		// even where the test never asked for them, and they hold the ingredient down.
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM master_recipes");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ------------------------------------------------------------------ E2-S9

	@Test
	@DisplayName("both books load, and loading twice leaves the same 44 rows")
	void loadsAndReloads() {
		LibraryLoader.Result first = loader.load();

		assertThat(first.books()).isEqualTo(EXPECTED_BOOKS);
		assertThat(first.recipes()).isEqualTo(EXPECTED_RECIPES);
		assertThat(count("master_recipes")).isEqualTo(EXPECTED_RECIPES);

		// Per book, so a book that lost recipes on the way in shows up here rather than as a gap
		// somebody notices next year. The books are no longer a uniform size — Karnataka holds 42 of
		// the 44 because that is what the temple cooks — so this names each one.
		List<Map<String, Object>> perBook = admin.queryForList("""
				SELECT state_slug, count(*) AS n FROM master_recipes
				GROUP BY state_slug ORDER BY state_slug
				""");
		assertThat(perBook).containsExactly(
				Map.of("state_slug", "andhra_pradesh", "n", 2L),
				Map.of("state_slug", "karnataka", "n", 42L));

		loader.load();
		assertThat(count("master_recipes")).isEqualTo(EXPECTED_RECIPES);
	}

	@Test
	@DisplayName("the 44 curated names are already distinct, so nothing is suffixed")
	void ladder() {
		LibraryLoader.Result result = loader.load();

		Integer distinct = admin.queryForObject(
				"SELECT count(DISTINCT lower(display_name)) FROM master_recipes", Integer.class);
		assertThat(distinct).isEqualTo(EXPECTED_RECIPES);

		// Every row on rung 0. Rajeev named each recipe once when he curated them, so the collisions
		// the ladder was built for — seventeen books with a Sabudana Khichdi, three of them different
		// dishes — do not exist in this catalogue at all. The ladder itself is still exercised, on a
		// fixture, by ladderSuffixesOnlyWhenNamesCollide below.
		assertThat(result.bare()).isEqualTo(EXPECTED_RECIPES);
		assertThat(result.withState()).isZero();
		assertThat(result.withStateAndCategory()).isZero();

		// And the consequence a reader cares about: no display name carries a parenthetical, so the
		// library screen shows "Chitranna" rather than "Chitranna (Karnataka)".
		List<String> suffixed = admin.queryForList(
				"SELECT display_name FROM master_recipes WHERE display_name LIKE '% (%'", String.class);
		assertThat(suffixed).isEmpty();
	}

	/**
	 * The ladder's second and third rungs, which the curated catalogue no longer reaches.
	 *
	 * <p>This used to be asserted on the real files: seventeen Sabudana Khichdis across seventeen
	 * state books for rung 1, and Karnataka's two Alugadde Palyas — one under Ekadashi with rock salt
	 * and no mustard, one under Sabji's Dry with a full tempering — for rung 2. Both went with the
	 * unvetted books. The code is still live and a temple may still meet it the day a second curated
	 * book repeats a name, so the cases are kept and driven from a two-book fixture instead.
	 *
	 * <p>The fixture also keeps the hole the two-pass count exists to close: the <em>first</em> Bisi
	 * Bele Bath has never been seen before, so a streaming "suffix it if I have seen this name" would
	 * let one of the two through bare, and which one depends on the order the files were read in.
	 */
	@Test
	@DisplayName("a name held by two books is suffixed with the state, and within one book with the category")
	void ladderSuffixesOnlyWhenNamesCollide() {
		LibraryLoader.Result result = loader.load("classpath:ladder-book/*.json");

		assertThat(result.books()).isEqualTo(2);
		assertThat(result.recipes()).isEqualTo(5);

		// Rung 0: a name only one recipe holds is left alone.
		assertThat(displayNamesOf("neer dose")).containsExactly("Neer Dose");

		// Rung 1: two books, one name, neither left bare — including the one read first.
		assertThat(displayNamesOf("bisi bele bath")).containsExactlyInAnyOrder(
				"Bisi Bele Bath (Ladder North)", "Bisi Bele Bath (Ladder South)");

		// Rung 2: one book, one name, two categories — the state alone does not separate them.
		assertThat(displayNamesOf("alugadde palya")).containsExactlyInAnyOrder(
				"Alugadde Palya (Ladder South, Ekadashi)",
				"Alugadde Palya (Ladder South, Sabji's, Dry)");

		assertThat(result.bare()).isEqualTo(1);
		assertThat(result.withState()).isEqualTo(2);
		assertThat(result.withStateAndCategory()).isEqualTo(2);
	}

	@Test
	@DisplayName("every yield and every ingredient quantity resolved — nothing was skipped")
	void everythingParsed() {
		loader.load();

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM master_recipes WHERE yield_qty IS NULL OR yield_qty <= 0",
				Integer.class)).isZero();

		// The three yield units the catalogue uses, in the proportions it uses them. Counted from the
		// two JSON files: 30 dishes made by the litre, 10 by the piece, 4 by the kilogram.
		assertThat(unitCount("L")).isEqualTo(30);
		assertThat(unitCount("PIECES")).isEqualTo(10);
		assertThat(unitCount("KG")).isEqualTo(4);
		assertThat(unitCount("L") + unitCount("PIECES") + unitCount("KG")).isEqualTo(EXPECTED_RECIPES);

		// 41 of the 44 state a portion a person can be served. The three that do not are the two
		// pickles, which nobody serves by the head, and Mysore Pak, which is made by the kilo and
		// portioned "140 gm per devotee" inside its yield string — a mass against a mass, but written
		// in a form the parenthetical rule does not read, so no number is invented from it.
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM master_recipes WHERE per_head_qty IS NOT NULL", Integer.class))
				.isEqualTo(41);
		assertThat(admin.queryForList("""
				SELECT name FROM master_recipes WHERE per_head_qty IS NULL ORDER BY name
				""", String.class)).containsExactly(
						"Limbe Uppinakayi", "Mavinakayi Uppinakayi", "Mysore Pak");
	}

	/**
	 * The two things Rajeev's curation adds that no vendored book ever carried (T-401, T-403).
	 *
	 * <p>{@code LibraryPreparationIT} and {@code RecipeImportNotBoughtIT} prove the reader and the
	 * import handle these keys, but both drive a fixture, because when they were written there was no
	 * real book that held either key. There is now, and this is the assertion that the real files
	 * still reach the table — the one that fails if a future run of
	 * {@code tools/seed/02b-build-catalogue.mjs} drops a field, or if the loader stops writing one.
	 */
	@Test
	@DisplayName("the preparations and the never-bought marks in the curated files reach master_recipes")
	void preparationsAndNeverBoughtMarksSurviveTheLoad() {
		loader.load();

		assertThat(admin.queryForObject("""
				SELECT count(*) FROM master_recipes m, jsonb_array_elements(m.ingredients) AS line
				""", Integer.class))
				.as("every ingredient line in both books")
				.isEqualTo(EXPECTED_INGREDIENT_LINES);

		assertThat(admin.queryForObject("""
				SELECT count(*) FROM master_recipes m, jsonb_array_elements(m.ingredients) AS line
				WHERE line->>'prep' IS NOT NULL
				""", Integer.class))
				.as("lines stating their own preparation")
				.isEqualTo(EXPECTED_PREPARATIONS);

		assertThat(admin.queryForObject("""
				SELECT count(*) FROM master_recipes m, jsonb_array_elements(m.ingredients) AS line
				WHERE (line->>'not_bought')::boolean
				""", Integer.class))
				.as("lines the temple never buys")
				.isEqualTo(EXPECTED_NOT_BOUGHT);

		// Both keys are written on every line, present or not, so a reader can tell "the book did not
		// say" from "the book said no" — and so an old row and a new one never read alike.
		//
		// jsonb_exists rather than the `?` operator: a literal question mark in a JDBC statement is a
		// bind placeholder, and PostgreSQL never sees it as jsonb's containment test.
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM master_recipes m, jsonb_array_elements(m.ingredients) AS line
				WHERE NOT jsonb_exists(line, 'prep') OR NOT jsonb_exists(line, 'not_bought')
				""", Integer.class))
				.as("no line may be missing either key")
				.isZero();

		// Every never-bought line is water, which is the whole of what the mark is for so far.
		assertThat(admin.queryForList("""
				SELECT DISTINCT line->>'name' FROM master_recipes m,
				     jsonb_array_elements(m.ingredients) AS line
				WHERE (line->>'not_bought')::boolean
				""", String.class)).containsExactly("Water");

		// And a spot check that a preparation is the word itself rather than an empty string left by
		// a field that was read but not carried.
		assertThat(admin.queryForObject("""
				SELECT line->>'prep' FROM master_recipes m, jsonb_array_elements(m.ingredients) AS line
				WHERE m.name = 'Akki Rotti' AND line->>'name' = 'Water'
				""", String.class)).isEqualTo("Hot");
	}

	// ------------------------------------------------------------------ isolation

	@Test
	@DisplayName("a cook may read the library; nobody but an operator may change it, and the database says so")
	void isolation() {
		loader.load();
		UUID any = admin.queryForObject("SELECT id FROM master_recipes LIMIT 1", UUID.class);

		signIn("uid-cook-a");
		assertThatCookCanRead(any);

		// The endpoint refuses first...
		assertThat(statusOfWriteAttempt()).isEqualTo(403);

		// ...and underneath it, so does the policy. Run as the application role carrying a temple
		// admin's identity — the strongest identity that is not an operator — with no application
		// code in the way at all.
		//
		// A DELETE refused by RLS removes nothing rather than raising: the USING clause filters the
		// rows away, so there is nothing to delete and nothing to complain about. That is the right
		// shape for a read-side control and the reason the assertion is a count, not a throw. A
		// write with a WITH CHECK — an insert — does raise, and that is asserted below.
		asUser("uid-admin-a", jdbc -> jdbc.update("DELETE FROM master_recipes WHERE id = ?", any));
		assertThat(count("master_recipes")).isEqualTo(EXPECTED_RECIPES);

		// A refused INSERT does raise — SQLSTATE 42501, which Spring surfaces as a grammar
		// exception rather than an access one. The assertion is that it was refused and that
		// nothing landed, not on the wording of a driver's message.
		assertThatThrownBy(() -> asUser("uid-admin-a", jdbc -> jdbc.update("""
				INSERT INTO master_recipes (state_slug, state, book_language, recipe_slug, name,
					display_name, category_key, category_name, badge, yield_text, yield_qty,
					yield_unit, why, ingredients, method, source_ref)
				VALUES ('x', 'X', 'English', 'x', 'X', 'X', 'x', 'X', 'Everyday', '1 L', 1, 'L',
					'because', '[]'::jsonb, '[]'::jsonb, 'hand')
				""")))
				.isInstanceOf(org.springframework.dao.DataAccessException.class);
		assertThat(count("master_recipes")).isEqualTo(EXPECTED_RECIPES);
	}

	@Test
	@DisplayName("deleting a temple leaves the library standing")
	void tenantDeletionLeavesLibrary() {
		loader.load();
		admin.queryForObject("SELECT delete_tenant_cascade(?)::text", String.class, templeB);
		assertThat(count("master_recipes")).isEqualTo(EXPECTED_RECIPES);
	}

	// ------------------------------------------------------------------ E2-S10

	@Test
	@DisplayName("one box finds the temple's own and the library's, and marks what is already taken")
	void search() throws Exception {
		loader.load();
		signIn("uid-admin-a");

		mvc.perform(authed(get("/api/v1/recipes/search").param("q", "chitranna")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.origin=='LIBRARY')]").exists());

		// Typing an ingredient finds the dishes that contain it.
		mvc.perform(authed(get("/api/v1/recipes/search").param("q", "asafoetida")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].origin").value("LIBRARY"));

		// The category name is searchable; prose is not. This used to be asserted on a tag —
		// "Jain-safe" is a fact about a dish — but the curated recipes carry no tags at all, so the
		// category stands in for the same weight in the document. Typing "ekadashi" has to find the
		// eight fasting dishes, because that is how a cook looks for them on a fast day.
		mvc.perform(authed(get("/api/v1/recipes/search").param("q", "ekadashi")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(8));

		// And the other half of the rule: a method step is not indexed. "Knead" appears in six
		// recipes' methods and in no name, subtitle, ingredient or category, so it finds nothing —
		// which is the point, since nearly every recipe boils, stirs and tempers something.
		mvc.perform(authed(get("/api/v1/recipes/search").param("q", "knead")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	@DisplayName("an empty box shows the temple's own and no library rows at all")
	void emptySearch() throws Exception {
		loader.load();
		signIn("uid-admin-a");

		mvc.perform(authed(get("/api/v1/recipes/search")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.origin=='LIBRARY')]").doesNotExist());
	}

	// ------------------------------------------------------------------ E2-S12

	@Test
	@DisplayName("adding a library recipe creates a full copy, its ingredients and its category")
	void importCreatesCopy() throws Exception {
		loader.load();
		signIn("uid-admin-a");
		UUID imported = libraryId(IMPORTED);

		mvc.perform(authed(post("/api/v1/recipes/import/{id}", imported)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value(IMPORTED));

		Map<String, Object> copy = admin.queryForMap("""
				SELECT r.name, r.base_yield_unit, r.per_head_qty, r.master_recipe_id, r.tenant_id,
				       c.name AS category
				FROM recipes r JOIN recipe_categories c ON c.id = r.category_id
				WHERE r.name = ?
				""", IMPORTED);
		assertThat(copy.get("tenant_id")).isEqualTo(templeA);
		assertThat(copy.get("base_yield_unit")).isEqualTo("L");
		assertThat(copy.get("master_recipe_id")).isEqualTo(imported);
		assertThat(copy.get("category")).isEqualTo("Rice");
		assertThat(copy.get("per_head_qty")).isNotNull();

		// The lines came across in the book's order, against ingredients created for the purpose.
		Integer lines = admin.queryForObject(
				"SELECT count(*) FROM recipe_ingredients WHERE tenant_id = ?", Integer.class, templeA);
		assertThat(lines).isEqualTo(IMPORTED_LINES);

		// Every created ingredient got a unit and a category — the column is NOT NULL, and the books
		// carry no category at all.
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM ingredients WHERE tenant_id = ? AND library_derived", Integer.class, templeA))
				.isEqualTo(IMPORTED_LINES);
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM ingredients WHERE tenant_id = ? AND (category IS NULL OR canonical_unit IS NULL)",
				Integer.class, templeA)).isZero();
	}

	/*
	 * T-119. The import's own marking, read back through the screens that now use it.
	 *
	 * `importCreatesCopy` above already counts the marked rows in the column. What this adds is the
	 * other half: that the mark reaches the client, that the count endpoint the Recipes screen reads
	 * agrees with it, and that saving one of them takes it back out again — proven by reading the
	 * row back, because a 204 looks the same whether the column moved or not.
	 */
	@Test
	@DisplayName("the ingredients an import creates are marked, counted, and cleared by an edit")
	void importedIngredientsAreMarkedAndTheCountFalls() throws Exception {
		loader.load();
		signIn("uid-admin-a");

		mvc.perform(authed(post("/api/v1/recipes/import/{id}", libraryId(IMPORTED))))
				.andExpect(status().isCreated());

		String catalogue = mvc.perform(authed(get("/api/v1/ingredients")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		List<Boolean> marks = JsonPath.read(catalogue, "$[*].libraryDerived");
		assertThat(marks).hasSize(IMPORTED_LINES).containsOnly(true);

		mvc.perform(authed(get("/api/v1/ingredients/library-derived-count")))
				.andExpect(jsonPath("$.count").value(IMPORTED_LINES));

		/*
		 * One of them reviewed: opened, CHANGED, and saved.
		 *
		 * It sent the row back byte for byte until T-121, when Rajeev changed what clears the mark —
		 * "when the user goes to edit mode, makes atleast one modification and saves" — so an
		 * unchanged save now leaves it standing and this test would have proved the opposite of what
		 * it says. The change made here is an alias, which is what reviewing one of these rows
		 * actually produces: an import knows the name the recipe book used and nothing else, and the
		 * temple's own word for the same thing is the first thing a person adds.
		 *
		 * The rule's other half — that an unchanged save changes nothing — is asserted in
		 * IngredientIT, next to the comparison itself.
		 */
		String id = JsonPath.read(catalogue, "$[0].id");
		String name = JsonPath.read(catalogue, "$[0].name");
		String category = JsonPath.read(catalogue, "$[0].category");
		String unit = JsonPath.read(catalogue, "$[0].unit");
		mvc.perform(authed(put("/api/v1/ingredients/{id}", UUID.fromString(id)))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"" + name + "\",\"category\":\"" + category
								+ "\",\"unit\":\"" + unit + "\",\"supply\":false,"
								+ "\"aliases\":[\"Temple word\"]}"))
				.andExpect(status().isNoContent());

		assertThat(admin.queryForObject(
				"SELECT library_derived FROM ingredients WHERE id = ?", Boolean.class,
				UUID.fromString(id)))
				.isFalse();
		mvc.perform(authed(get("/api/v1/ingredients/library-derived-count")))
				.andExpect(jsonPath("$.count").value(IMPORTED_LINES - 1));
	}

	@Test
	@DisplayName("a second import of the same recipe is refused, and so is one whose name is taken")
	void refusesDuplicates() throws Exception {
		loader.load();
		signIn("uid-admin-a");
		UUID imported = libraryId(IMPORTED);

		mvc.perform(authed(post("/api/v1/recipes/import/{id}", imported))).andExpect(status().isCreated());

		mvc.perform(authed(post("/api/v1/recipes/import/{id}", imported)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400103"));

		// And the name rule, which is what a temple that typed the dish in by hand last year meets.
		admin.update("DELETE FROM recipe_ingredients");
		admin.update("UPDATE recipes SET master_recipe_id = NULL WHERE tenant_id = ?", templeA);
		mvc.perform(authed(post("/api/v1/recipes/import/{id}", imported)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400036"));
	}

	@Test
	@DisplayName("an import naming an ingredient the temple already holds no longer has a refusal to make")
	void noLongerRefusesProhibited() throws Exception {
		// This test asserted the opposite until 2026-09-08: with an ingredient flagged
		// sattvic-prohibited, the import was refused with KMS-400104 and left nothing behind.
		// D-18 deleted the flag and retired the code, so the refusal has no input and cannot fire.
		// D-18 names this refusal specifically as a live guard given up on purpose, on the reasoning
		// that the library is the temple's own and does not carry such ingredients — so the inverse
		// is asserted here rather than the test simply being deleted, because "the import completes"
		// is now the product's behaviour and somebody should be told when it stops being true.
		//
		// The pre-existing row was 'Curd, fresh', which Majjige named; the curated catalogue has no
		// Majjige, so it is one of Chitranna's own lines instead. The case being made is the same:
		// an ingredient the temple already holds is matched, not duplicated.
		loader.load();
		signIn("uid-admin-a");
		UUID imported = libraryId(IMPORTED);

		admin.update("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Turmeric', 'Spices', 'GM')
				""", templeA);

		mvc.perform(authed(post("/api/v1/recipes/import/{id}", imported)))
				.andExpect(status().isCreated());

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM recipes WHERE tenant_id = ?", Integer.class, templeA)).isEqualTo(1);
		// The pre-existing row was matched on lower(name) rather than duplicated, and the rest of the
		// recipe's ingredients were created around it.
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM ingredients WHERE tenant_id = ? AND lower(name) = 'turmeric'
				""", Integer.class, templeA)).isEqualTo(1);
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM ingredients WHERE tenant_id = ?", Integer.class, templeA))
				.as("the import created the rest of " + IMPORTED + "'s lines")
				.isEqualTo(IMPORTED_LINES);

		// And nothing it created carries a dietary flag — the gap the Recipes page now warns about.
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM ingredients WHERE tenant_id = ? AND is_ekadashi_prohibited
				""", Integer.class, templeA)).isZero();
	}

	@Test
	@DisplayName("no curated recipe names onion or garlic at all, in any line")
	void nothingProhibitedIsInTheCatalogue() {
		loader.load();

		// This case used to import Jharkhand's Bhuja, whose lines named "Onion-free chaat masala" and
		// "Garlic-free panch phoron" — the only two ingredient names in the vendored library carrying
		// either word, both describing their ABSENCE — to show that nothing reads dietary meaning out
		// of the letters in a name. Jharkhand's book went with the unvetted import.
		//
		// What replaces it is the stronger statement Rajeev's curation makes: the two words appear
		// nowhere in the catalogue, because he approved every line by hand. This is the test that
		// fails if a future curated recipe brings one in, which is worth being told about loudly.
		List<String> offending = admin.queryForList("""
				SELECT DISTINCT m.name || ' — ' || (line->>'name')
				FROM master_recipes m, jsonb_array_elements(m.ingredients) AS line
				WHERE lower(line->>'name') LIKE '%onion%' OR lower(line->>'name') LIKE '%garlic%'
				ORDER BY 1
				""", String.class);
		assertThat(offending).isEmpty();
	}

	@Test
	@DisplayName("a copy is the temple's own: editing it changes nothing in the library or elsewhere")
	void copyIsIndependent() throws Exception {
		loader.load();
		signIn("uid-admin-a");
		UUID imported = libraryId(IMPORTED);
		mvc.perform(authed(post("/api/v1/recipes/import/{id}", imported))).andExpect(status().isCreated());

		admin.update("UPDATE recipes SET per_head_qty = 0.5 WHERE tenant_id = ?", templeA);

		// Chitranna is 30 L serving 300 ml a head, so the library's own figure is 0.3 and the edit
		// above did not reach it.
		assertThat(admin.queryForObject(
				"SELECT per_head_qty FROM master_recipes WHERE id = ?", java.math.BigDecimal.class, imported))
				.isEqualByComparingTo("0.3");

		// And temple B sees none of it.
		signIn("uid-admin-b");
		mvc.perform(authed(get("/api/v1/recipes/search").param("q", "chitranna")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.origin=='MINE')]").doesNotExist());
	}

	/**
	 * The three ways the library screen actually asks, and the reason this exists.
	 *
	 * <p>Every one of these returned 500 in production on 2026-09-07. {@code browse} filtered with
	 * {@code (? IS NULL OR m.state_slug = ?)}, and an untyped placeholder inside {@code ? IS NULL}
	 * gives PostgreSQL nothing to infer a type from — "could not determine data type of parameter
	 * $1". The only call that worked was the one supplying both filters, which is the one the screen
	 * never makes on opening. It had been that way since the library shipped on 2026-08-22, and no
	 * test asked for a list without both filters, so nothing caught it.
	 */
	@Test
	@DisplayName("browsing works with no filter, with a state alone, and with both")
	void browsesWithAnyCombinationOfFilters() throws Exception {
		loader.load();
		signIn("uid-admin-a");

		// No filter at all — what the screen asks for the moment it opens. The whole catalogue now
		// fits inside one page of 100, so this is all 44 rather than a truncated first page.
		mvc.perform(authed(get("/api/v1/library/recipes").param("limit", "100")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(EXPECTED_RECIPES));

		// A state alone, which is the first thing anybody clicks.
		mvc.perform(authed(get("/api/v1/library/recipes")
						.param("state", "karnataka").param("limit", "100")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].state").value("Karnataka"));

		// Both, which is the combination that happened to work before the fix.
		mvc.perform(authed(get("/api/v1/library/recipes")
						.param("state", "karnataka").param("category", "rice").param("limit", "100")))
				.andExpect(status().isOk());
	}

	/**
	 * One order everywhere recipes are listed: state A–Z, then name A–Z (Rajeev, 2026-09-07).
	 *
	 * <p>"This makes it easy for people to do a visual search rather than using the search box."
	 * Browsing used to sort by category in the middle, which broke the alphabet into twenty-one
	 * runs per state; searching used to sort by rank, which reorders the list under the reader on
	 * every keystroke. Both now read the same way down the page.
	 */
	@Test
	@DisplayName("every listing reads state A-Z then name A-Z, browsing or searching")
	void ordersForReading() throws Exception {
		loader.load();
		signIn("uid-admin-a");

		// Browsing everything: Andhra Pradesh first, and its own names in one alphabetical run.
		String browsed = mvc.perform(authed(get("/api/v1/library/recipes").param("limit", "300")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].state").value("Andhra Pradesh"))
				.andReturn().getResponse().getContentAsString();
		assertThat(namesWithin(browsed, "Andhra Pradesh"))
				.isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);

		// And a search, which used to come back ranked. "salt" is the term chosen because it reaches
		// both books — 35 Karnataka rows and 2 Andhra Pradesh ones — so the state ordering is
		// actually being asserted rather than trivially satisfied by a single-state result.
		String searched = mvc.perform(authed(get("/api/v1/library/recipes")
						.param("q", "salt").param("limit", "300")))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		List<String> states = JsonPath.parse(searched).read("$[*].state");
		assertThat(states).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
	}

	/** Every display name the ladder gave one underlying recipe name, alphabetically. */
	private List<String> displayNamesOf(String lowerName) {
		return admin.queryForList(
				"SELECT display_name FROM master_recipes WHERE lower(name) = ? ORDER BY display_name",
				String.class, lowerName);
	}

	/** The display names belonging to one state, in the order the server returned them. */
	private List<String> namesWithin(String json, String state) {
		return JsonPath.parse(json).read("$[?(@.state=='" + state + "')].displayName");
	}

	// ------------------------------------------------------------------ helpers

	private void assertThatCookCanRead(UUID id) {
		try {
			mvc.perform(authed(get("/api/v1/library/recipes/{id}", id))).andExpect(status().isOk());
		} catch (Exception e) {
			throw new AssertionError("a cook could not read the library", e);
		}
	}

	private int statusOfWriteAttempt() {
		try {
			return mvc.perform(authed(post("/api/v1/library/recipes/load")))
					.andReturn().getResponse().getStatus();
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * Runs a statement as the unprivileged application role, carrying one person's verified
	 * identity. The point of going round the application: the policy has to refuse this on its own,
	 * with no Java in the way.
	 */
	private void asUser(String uid, java.util.function.Consumer<JdbcTemplate> work) {
		org.springframework.jdbc.datasource.DriverManagerDataSource plain =
				new org.springframework.jdbc.datasource.DriverManagerDataSource();
		plain.setUrl(POSTGRES.getJdbcUrl());
		plain.setUsername(APP_ROLE);
		plain.setPassword(APP_PASSWORD);

		org.iskcon.kms.tenancy.TenantContext.setAuthLookupUid(uid);
		try {
			work.accept(new JdbcTemplate(new org.iskcon.kms.tenancy.TenantAwareDataSource(plain)));
		} finally {
			org.iskcon.kms.tenancy.TenantContext.clear();
		}
	}

	private UUID libraryId(String name) {
		return admin.queryForObject(
				"SELECT id FROM master_recipes WHERE display_name = ? LIMIT 1", UUID.class, name);
	}

	private int count(String table) {
		Integer n = admin.queryForObject("SELECT count(*) FROM " + table, Integer.class);
		return n == null ? 0 : n;
	}

	private int unitCount(String unit) {
		Integer n = admin.queryForObject(
				"SELECT count(*) FROM master_recipes WHERE yield_unit = ?", Integer.class, unit);
		return n == null ? 0 : n;
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private UUID insertTenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, name);
	}

	private void insertUser(UUID tenantId, String uid, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				""", tenantId, uid, uid + "@example.com", role);
	}

	private void insertOperator(String uid) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, ?, 'Platform Operator', ?, '+919876500099', 'SUPER_ADMIN', 'ACTIVE')
				""", uid, uid + "@example.com");
	}

	/** The nine a temple is provisioned with, so an import can find Beverages without creating it. */
	private void seedCategories(UUID tenantId) {
		for (String name : new String[] {
				"Beverages", "Breakfast", "Rice", "Dal", "Sabji", "Roti", "Sweets", "Snacks"}) {
			admin.update("""
					INSERT INTO recipe_categories (tenant_id, name, fasting_compatible)
					VALUES (?, ?, false)
					""", tenantId, name);
		}
		admin.update("""
				INSERT INTO recipe_categories (tenant_id, name, fasting_compatible)
				VALUES (?, 'Ekadashi', true)
				""", tenantId);
	}

}
