package org.iskcon.kms.library;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
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
 * A line's preparation survives the whole way from the book to the temple's recipe (T-401).
 *
 * <p><strong>What was wrong.</strong> The books vendored so far write a preparation inside the
 * ingredient's name, after a comma — "Green chilli, slit" — and nothing ever stored it separately:
 * {@code LibraryLoader} wrote name, qty, qtyValue, qtyUnit and scaled, and the import recovered the
 * preparation at the far end by splitting the name again. Rajeev's curated recipes (2026-09-19) name
 * the ingredient plainly and put the preparation in a {@code prep} key of its own, 84 of them across
 * 45 recipes. Against a curated line the split finds nothing, so every one of those notes imported
 * empty and no screen could have shown them either.
 *
 * <p>Four things had to hold, and each has a test here: the loader carries {@code prep} across, the
 * read API returns it, the operator's editor cannot quietly erase it, and the import prefers it over
 * splitting the name — while a book that still says it inside the name keeps working exactly as
 * before.
 *
 * <p><strong>Why a fixture book.</strong> {@link RecipeLibraryIT} loads the real catalogue on
 * purpose, and says why: a fixture proves only that the loader can read a file somebody wrote to
 * make the test pass. When this was written there was no real book that said {@code prep} at all,
 * so there was nothing real to read. There is now — the curated catalogue replaced the 32 vendored
 * books on 2026-09-19 — and
 * {@code RecipeLibraryIT.preparationsAndNeverBoughtMarksSurviveTheLoad} asserts the real files reach
 * the table, 83 preparations and 15 marks of them.
 *
 * <p>This class is kept on its fixture regardless, and should stay there. It covers the shapes a
 * curated book does <em>not</em> contain — a line that still writes its preparation inside the name
 * after a comma, a line with no preparation at all beside one that has one — and it must keep
 * working when the catalogue is regenerated and every count in it moves. The fixture at
 * {@code src/test/resources/prep-book} is one recipe, loaded through the package-private
 * {@code LibraryLoader.load(String)} so the real catalogue is untouched.
 */
@AutoConfigureMockMvc
class LibraryPreparationIT extends AbstractIntegrationTest {

	/** The one fixture book, kept out of {@code recipe-library} so a real load never sees it. */
	private static final String FIXTURE_BOOK = "classpath:prep-book/*.json";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private LibraryLoader loader;

	private JdbcTemplate admin;
	private UUID temple;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		temple = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		insertUser(temple, "uid-admin-a", "TEMPLE_ADMIN");
		insertOperator("uid-operator");
		stubVerifier.accept("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM master_recipes");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ------------------------------------------------------------------ the loader and the API

	@Test
	@DisplayName("a load reads prep out of a book, stores it, and GET returns it — null where the book says nothing")
	void loadCarriesPrepThroughToTheApi() throws Exception {
		loader.load(FIXTURE_BOOK);

		UUID id = admin.queryForObject(
				"SELECT id FROM master_recipes WHERE recipe_slug = 'akki-rotti-curated'", UUID.class);

		// In the row itself, first: the key is present on every line, and written as an explicit
		// JSON null where the book has no preparation. Absent and null would read alike to the
		// reader, and then nobody could tell an old row from a new one. Cast to text rather than
		// read with ->>, which turns a JSON null into a SQL NULL and loses that very distinction:
		// an absent key gives NULL here, a JSON null gives the four characters "null".
		assertThat(storedPreps(id)).containsExactly("null", "\"Hot\"", "\"Grated\"", "\"Chopped\"");

		String body = mvc.perform(authed(get("/api/v1/library/recipes/{id}", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ingredients[0].name").value("Rice flour"))
				.andReturn().getResponse().getContentAsString();

		// An indefinite path returns one element per line that HAS the key, so a list of four with a
		// null first is the whole assertion: the server sends prep on every line, null where there
		// is none. A response that left the key off a bare line would come back with three.
		assertThat(JsonPath.<List<String>>read(body, "$.ingredients[*].prep"))
				.containsExactly(null, "Hot", "Grated", "Chopped");
	}

	@Test
	@DisplayName("the operator's editor round-trips it: reading a curated recipe and saving it back keeps every note")
	void operatorsSaveKeepsThePreparations() throws Exception {
		loader.load(FIXTURE_BOOK);
		UUID id = admin.queryForObject(
				"SELECT id FROM master_recipes WHERE recipe_slug = 'akki-rotti-curated'", UUID.class);

		// An operator opens it and presses save with nothing changed but the subtitle. Before T-401
		// the editor's jsonb had no prep in it at all, so this request alone wiped all three notes.
		stubVerifier.accept("uid-operator");
		mvc.perform(authed(put("/api/v1/library/recipes/{id}", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "name": "Akki Rotti Curated",
						  "subtitle": "Rice flour flatbread, from the curated set",
						  "state": "Prep Fixture", "stateSlug": "prep-fixture",
						  "recipeSlug": "akki-rotti-curated",
						  "categoryKey": "breakfast-items", "categoryName": "Breakfast Items",
						  "badge": "Everyday",
						  "yieldText": "200 rottis", "yieldQty": 200, "yieldUnit": "PIECES",
						  "why": "Because the notes have to survive a save.",
						  "ingredients": [
						    {"name": "Rice flour", "qty": "12 Kg"},
						    {"name": "Water", "qty": "14 L", "prep": "Hot"},
						    {"name": "Coconut", "qty": "16 Pieces", "prep": "Grated"},
						    {"name": "Green chilli", "qty": "250 gm", "prep": "  Chopped  "}
						  ],
						  "method": ["Mix.", "Cook."]
						}
						"""))
				.andExpect(status().isNoContent());

		String body = mvc.perform(authed(get("/api/v1/library/recipes/{id}", id)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		// The last one was sent with spaces round it and comes back trimmed, like every other
		// written field here.
		assertThat(JsonPath.<List<String>>read(body, "$.ingredients[*].prep"))
				.containsExactly(null, "Hot", "Grated", "Chopped");

		// And the line the operator left alone still holds a JSON null rather than losing its key.
		assertThat(storedPreps(id)).containsExactly("null", "\"Hot\"", "\"Grated\"", "\"Chopped\"");
	}

	// ------------------------------------------------------------------ the import

	@Test
	@DisplayName("a curated line — no comma, prep \"Roasted\" — imports as the whole name with that note")
	void aStatedPreparationIsTheNote() throws Exception {
		UUID master = insertLibraryRecipe("Rava Ladoo",
				line("Cashew", "100 gm", "100", "GM", "Roasted"));

		String recipeId = importIt(master);

		assertThat(ingredientNames()).containsExactly("Cashew");
		assertThat(noteOn(recipeId)).isEqualTo("Roasted");
		assertThat(ingredientOfFirstLine(recipeId)).isEqualTo("Cashew");
	}

	@Test
	@DisplayName("a curated two-word name is not taken apart: \"Green chilli\" + \"Slit\" stays Green chilli")
	void theWholeNameIsTheIngredient() throws Exception {
		// "Green chilli" would survive a split anyway; "Grated coconut" would not — split takes
		// "grated" off the front and leaves Coconut. A curated line saying its preparation outright
		// is not guessed at, so the name the temple gets is the name the book wrote.
		UUID master = insertLibraryRecipe("Coconut Chutney",
				line("Grated coconut", "200 gm", "200", "GM", "Roasted lightly"));

		String recipeId = importIt(master);

		assertThat(ingredientNames()).containsExactly("Grated coconut");
		assertThat(noteOn(recipeId)).isEqualTo("Roasted lightly");
	}

	@Test
	@DisplayName("a legacy comma line with no prep still splits: \"Cashew, halved\" is Cashew · halved")
	void aCommaNameStillSplits() throws Exception {
		// The behaviour every vendored book depends on, asserted rather than assumed. Written with
		// prep as an explicit JSON null, which is the shape the loader now stores for a book line
		// that says nothing — RecipeImportPreparationIT covers the older shape, with no key at all.
		UUID master = insertLibraryRecipe("Cashew Kheer",
				line("Cashew, halved", "100 gm", "100", "GM", null));

		String recipeId = importIt(master);

		assertThat(ingredientNames()).containsExactly("Cashew");
		assertThat(noteOn(recipeId)).isEqualTo("halved");
	}

	@Test
	@DisplayName("a blank prep is no prep: the name is still split")
	void aBlankPrepFallsBackToTheSplit() throws Exception {
		UUID master = insertLibraryRecipe("Cashew Kheer",
				line("Cashew, halved", "100 gm", "100", "GM", "   "));

		String recipeId = importIt(master);

		assertThat(ingredientNames()).containsExactly("Cashew");
		assertThat(noteOn(recipeId)).isEqualTo("halved");
	}

	@Test
	@DisplayName("no comma and no prep leaves no note at all — null in the column, not an empty string")
	void neitherLeavesNoNote() throws Exception {
		UUID master = insertLibraryRecipe("Plain Rice", line("Rice flour", "12 Kg", "12", "KG", null));

		String recipeId = importIt(master);

		assertThat(ingredientNames()).containsExactly("Rice flour");
		// queryForList would hand back a null for a missing row as readily as for a null column, so
		// the stored value is inspected directly: is it null, and is there a row at all.
		Map<String, Object> row = admin.queryForMap(
				"SELECT preparation_note, preparation_note IS NULL AS is_null"
						+ " FROM recipe_ingredients WHERE recipe_id = ?::uuid", recipeId);
		assertThat(row.get("is_null")).isEqualTo(true);
		assertThat(row.get("preparation_note")).isNull();
	}

	@Test
	@DisplayName("a stated preparation joins what is left of the name when the person says \"Use Rice\" (A-N5)")
	void aStatedPreparationJoinsTheLeftOver() throws Exception {
		// A curated name may still carry a comma where the comma says what the temple buys. The
		// person answers "Use Rice", so "basmati" is the rest of what the library wrote and belongs
		// on the note beside the preparation — in the order the two were written.
		UUID rice = insertIngredient("Rice", "KG");
		UUID master = insertLibraryRecipe("Basmati Pulao",
				line("Rice, basmati", "1 Kg", "1", "KG", "Soaked"));

		String body = mvc.perform(authed(post("/api/v1/recipes/import/{id}", master))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"decisions": [
						  {"libraryName": "Rice, basmati", "useIngredientId": "%s", "confirmDifferent": false}
						]}
						""".formatted(rice)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		String recipeId = JsonPath.read(body, "$.id");
		assertThat(ingredientNames()).containsExactly("Rice");
		assertThat(noteOn(recipeId)).isEqualTo("basmati, Soaked");
	}

	// ------------------------------------------------------------------ fixtures

	private record Line(String name, String qty, String qtyValue, String qtyUnit, String prep) {
	}

	private static Line line(String name, String qty, String qtyValue, String qtyUnit, String prep) {
		return new Line(name, qty, qtyValue, qtyUnit, prep);
	}

	/**
	 * A library recipe written straight into the table, in the shape the loader now writes: every
	 * line carries a {@code prep} key, and a line with no preparation carries it as a JSON null.
	 */
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
					.append(",\"qtyUnit\":\"").append(lines[i].qtyUnit()).append("\",\"prep\":")
					.append(lines[i].prep() == null ? "null" : "\"" + lines[i].prep() + "\"")
					.append('}');
			names.append('"').append(lines[i].name()).append('"');
		}
		json.append(']');
		names.append('}');
		String slug = name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
		return admin.queryForObject("""
				INSERT INTO master_recipes (state_slug, state, book_language, recipe_slug, name,
					display_name, category_key, category_name, badge, yield_text, yield_qty,
					yield_unit, why, ingredient_names, ingredients, method, source_ref)
				VALUES ('karnataka', 'Karnataka', 'English', ?, ?, ?, 'rice', 'Rice', 'Everyday',
					'10 Kg', 10, 'KG', 'because', CAST(? AS text[]), CAST(? AS jsonb), '["Mix."]'::jsonb, 'test')
				RETURNING id
				""", UUID.class, slug, name, name, names.toString(), json.toString());
	}

	private String importIt(UUID master) throws Exception {
		String body = mvc.perform(authed(post("/api/v1/recipes/import/{id}", master)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	/** Each line's {@code prep} as jsonb text, in array order: "null" for a JSON null, NULL if absent. */
	private List<String> storedPreps(UUID id) {
		return admin.queryForList("""
				SELECT (line -> 'prep')::text
				FROM master_recipes m,
				     LATERAL jsonb_array_elements(m.ingredients) WITH ORDINALITY AS t(line, n)
				WHERE m.id = ?
				ORDER BY t.n
				""", String.class, id);
	}

	private String noteOn(String recipeId) {
		return admin.queryForObject(
				"SELECT preparation_note FROM recipe_ingredients WHERE recipe_id = ?::uuid ORDER BY line_order",
				String.class, recipeId);
	}

	private String ingredientOfFirstLine(String recipeId) {
		return admin.queryForObject("""
				SELECT i.name FROM recipe_ingredients ri JOIN ingredients i ON i.id = ri.ingredient_id
				WHERE ri.recipe_id = ?::uuid ORDER BY ri.line_order LIMIT 1
				""", String.class, recipeId);
	}

	private List<String> ingredientNames() {
		return admin.queryForList(
				"SELECT name FROM ingredients WHERE tenant_id = ? ORDER BY name", String.class, temple);
	}

	private UUID insertIngredient(String name, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Test', ?) RETURNING id
				""", UUID.class, temple, name, unit);
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
}
