package org.iskcon.kms.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Taking a temple's copy of a library recipe no longer turns a preparation into an ingredient
 * (R-DUP-1, and R-DUP-2's clause for the library import). docs/work/PROCUREMENT-REQUIREMENTS.md §9A.
 *
 * <p>The library recipes here are written straight into {@code master_recipes} rather than loaded
 * from the vendored books, because each test needs a line the books may or may not happen to hold in
 * one recipe — "Green chilli, slit" beside "Green chillies", or "Dahi" reaching Curd through an alias
 * — and a fixture says which. {@link RecipeLibraryIT} still imports a real book recipe end to end.
 *
 * <p>Everything runs through the HTTP endpoint as the temple's own admin, so RLS is in the path: the
 * catalogue the matcher reads is the one the database lets this temple see.
 */
@AutoConfigureMockMvc
class RecipeImportPreparationIT extends AbstractIntegrationTest {

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
		insertUser(templeA, "uid-admin-a", "TEMPLE_ADMIN");
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
	@DisplayName("AC R-DUP-1: \"Green chilli, slit\" creates no ingredient when Green chilli exists, and the line reads Green chilli · slit")
	void greenChilliSlitUsesTheGreenChilliTheTempleHas() throws Exception {
		UUID chilli = insertIngredient(templeA, "Green chilli", "GM");
		int before = ingredientCount(templeA);

		UUID master = insertLibraryRecipe("Chilli Majjige", line("Green chilli, slit", "20 gm", "20", "GM"));
		String recipeId = importIt(master);

		assertThat(ingredientCount(templeA)).as("no ingredient was created").isEqualTo(before);
		assertThat(ingredientNames(templeA)).containsExactly("Green chilli");

		Map<String, Object> row = admin.queryForMap(
				"SELECT ingredient_id, preparation_note FROM recipe_ingredients WHERE recipe_id = ?::uuid",
				recipeId);
		assertThat(row.get("ingredient_id")).isEqualTo(chilli);
		assertThat(row.get("preparation_note")).isEqualTo("slit");

		// What the recipe page reads: the base ingredient's name and the note, which the screen joins
		// as "Green chilli · slit". The joined form itself is checked on screen (see the proof).
		mvc.perform(authed(get("/api/v1/recipes/{id}", recipeId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ingredients[0].ingredientName").value("Green chilli"))
				.andExpect(jsonPath("$.ingredients[0].preparationNote").value("slit"));
	}

	@Test
	@DisplayName("with no match, only the base ingredient is created — Cashew, never \"Cashew, halved\"")
	void noMatchCreatesTheBaseOnly() throws Exception {
		UUID master = insertLibraryRecipe("Cashew Kheer", line("Cashew, halved", "100 gm", "100", "GM"));
		String body = importBody(master);

		assertThat(ingredientNames(templeA)).containsExactly("Cashew");
		assertThat(JsonPath.<Integer>read(body, "$.ingredientsCreated")).isEqualTo(1);

		Map<String, Object> created = admin.queryForMap(
				"SELECT name, canonical_unit, library_derived FROM ingredients WHERE tenant_id = ?", templeA);
		assertThat(created.get("canonical_unit")).isEqualTo("GM");
		assertThat(created.get("library_derived")).isEqualTo(true);
		assertThat(notes(JsonPath.read(body, "$.id"))).containsExactly("halved");
	}

	@Test
	@DisplayName("two preparations of one thing in one recipe make one ingredient and two notes")
	void twoPreparationsOfOneThingInOneRecipe() throws Exception {
		UUID master = insertLibraryRecipe("Coconut Chutney",
				line("Coconut, grated", "200 gm", "200", "GM"),
				line("Fresh grated coconut", "50 gm", "50", "GM"),
				line("Green chilli, slit", "10 gm", "10", "GM"),
				line("Green chillies", "5 gm", "5", "GM"));
		String body = importBody(master);

		// The second coconut line finds the Coconut the first line created a moment earlier; the
		// same for the chillies, through the plural.
		assertThat(ingredientNames(templeA)).containsExactly("Coconut", "Green chilli");
		assertThat(JsonPath.<Integer>read(body, "$.ingredientsCreated")).isEqualTo(2);
		assertThat(notes(JsonPath.read(body, "$.id")))
				.containsExactly("grated", "fresh grated", "slit", null);
	}

	@Test
	@DisplayName("a name merged away, kept as an alias, still finds the kept ingredient")
	void anAliasFindsTheKeptIngredient() throws Exception {
		UUID curd = insertIngredient(templeA, "Curd", "GM");
		admin.update("""
				INSERT INTO ingredient_aliases (tenant_id, ingredient_id, alias, normalised_alias)
				VALUES (?, ?, 'Dahi', 'dahi')
				""", templeA, curd);

		UUID master = insertLibraryRecipe("Raita", line("Dahi, whisked", "500 gm", "500", "GM"));
		String recipeId = importIt(master);

		assertThat(ingredientNames(templeA)).containsExactly("Curd");
		assertThat(admin.queryForObject(
				"SELECT ingredient_id FROM recipe_ingredients WHERE recipe_id = ?::uuid", UUID.class, recipeId))
				.isEqualTo(curd);
		assertThat(notes(recipeId)).containsExactly("whisked");
	}

	@Test
	@DisplayName("a store-bought form is not a preparation: \"Rice, broken\" stays one ingredient with no note")
	void aStoreBoughtFormStaysWhole() throws Exception {
		UUID master = insertLibraryRecipe("Broken Rice Upma", line("Rice, broken", "1 Kg", "1", "KG"));
		String recipeId = importIt(master);

		assertThat(ingredientNames(templeA)).containsExactly("Rice, broken");
		assertThat(notes(recipeId)).containsExactly((String) null);
	}

	@Test
	@DisplayName("Q-11 answered: a close-only match is no longer created silently — the copy asks, and writes nothing until answered")
	void aCloseMatchIsAskedAboutNotCreated() throws Exception {
		// "Jaggary" is one letter from "Jaggery" (a slip), and "Tomato, ripe" beside a bare
		// "Tomato" is the qualified-against-bare rule. Both are CLOSE, not EXACT. Until Rajeev's
		// answer to Q-11 (2026-09-19) the import created both; now it refuses until each is answered,
		// and RecipeImportCloseMatchIT covers the answers themselves.
		insertIngredient(templeA, "Jaggery", "GM");
		UUID tomato = insertIngredient(templeA, "Tomato", "GM");

		UUID master = insertLibraryRecipe("Tomato Saaru",
				line("Jaggary, grated", "20 gm", "20", "GM"),
				line("Tomato, ripe", "300 gm", "300", "GM"));

		mvc.perform(authed(post("/api/v1/recipes/import/{id}", master)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400156"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("closeMatches[0].libraryName"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("Jaggary"))
				.andExpect(jsonPath("$.fieldErrors[1].field").value("closeMatches[0].note"))
				.andExpect(jsonPath("$.fieldErrors[1].message").value("grated"));
		assertThat(ingredientNames(templeA)).containsExactly("Jaggery", "Tomato");
		assertThat(admin.queryForObject("SELECT count(*) FROM recipes", Integer.class)).isZero();

		// Answered — Jaggary is the temple's Jaggery, "Tomato, ripe" really is its own ingredient —
		// the copy goes through. Still split: a preparation is never an ingredient.
		UUID jaggery = admin.queryForObject(
				"SELECT id FROM ingredients WHERE tenant_id = ? AND name = 'Jaggery'", UUID.class, templeA);
		String body = mvc.perform(authed(post("/api/v1/recipes/import/{id}", master))
						.contentType("application/json")
						.content("{\"decisions\":["
								+ "{\"libraryName\":\"Jaggary\",\"useIngredientId\":\"" + jaggery + "\",\"confirmDifferent\":false},"
								+ "{\"libraryName\":\"Tomato, ripe\",\"useIngredientId\":null,\"confirmDifferent\":true}]}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String recipeId = JsonPath.read(body, "$.id");

		assertThat(ingredientNames(templeA)).containsExactly("Jaggery", "Tomato", "Tomato, ripe");
		assertThat(notes(recipeId)).containsExactly("grated", null);
		assertThat(admin.queryForList(
				"SELECT ingredient_id FROM recipe_ingredients WHERE recipe_id = ?::uuid ORDER BY line_order",
				UUID.class, recipeId).get(0)).isEqualTo(jaggery).isNotEqualTo(tomato);
	}

	@Test
	@DisplayName("another temple's Green chilli is never a match")
	void anotherTemplesIngredientIsNotAMatch() throws Exception {
		insertIngredient(templeB, "Green chilli", "GM");

		UUID master = insertLibraryRecipe("Chilli Majjige", line("Green chilli, slit", "20 gm", "20", "GM"));
		importIt(master);

		assertThat(ingredientNames(templeA)).containsExactly("Green chilli");
		assertThat(ingredientNames(templeB)).containsExactly("Green chilli");
	}

	// ---------------------------------------------------------------------

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

	private String importIt(UUID master) throws Exception {
		return JsonPath.read(importBody(master), "$.id");
	}

	private String importBody(UUID master) throws Exception {
		return mvc.perform(authed(post("/api/v1/recipes/import/{id}", master)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
	}

	/** The recipe's preparation notes, in line order. */
	private List<String> notes(String recipeId) {
		return admin.queryForList(
				"SELECT preparation_note FROM recipe_ingredients WHERE recipe_id = ?::uuid ORDER BY line_order",
				String.class, recipeId);
	}

	private List<String> ingredientNames(UUID tenant) {
		return admin.queryForList(
				"SELECT name FROM ingredients WHERE tenant_id = ? ORDER BY name", String.class, tenant);
	}

	private int ingredientCount(UUID tenant) {
		Integer n = admin.queryForObject(
				"SELECT count(*) FROM ingredients WHERE tenant_id = ?", Integer.class, tenant);
		return n == null ? 0 : n;
	}

	private UUID insertIngredient(UUID tenant, String name, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Test', ?) RETURNING id
				""", UUID.class, tenant, name, unit);
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
}
