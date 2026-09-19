package org.iskcon.kms.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.meal.MealFixture;
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

/** Recipe CRUD (E2-S2) through the full stack: RLS, cross-tenant reference rejection, search, archive. */
@AutoConfigureMockMvc
class RecipeIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID templeB;
	private UUID categoryRice;
	private UUID rice;
	private UUID dal;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
		insertUser(templeA, "uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");
		categoryRice = insertCategory(templeA, "Rice", false);
		rice = insertIngredient(templeA, "Rice", "Grains");
		dal = insertIngredient(templeA, "Toor Dal", "Pulses");
		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM recipe_ingredients");
		// Ahead of recipes: meal_dishes.recipe_id is ON DELETE RESTRICT, which is the whole reason a
		// cooked recipe cannot be deleted, and it holds this clean-up up just the same.
		MealFixture.deleteAll(admin);
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		// Anything that moved through the stock ledger is tracked now, so the item rows exist
		// even where the test never asked for them, and they hold the ingredient down.
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a recipe is created with lines, listed, fetched, and the creation is audited")
	void createsListsAndGets() throws Exception {
		String id = createKhichdi();

		mvc.perform(authed(get("/api/v1/recipes")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.name=='Khichdi')]").exists());

		mvc.perform(authed(get("/api/v1/recipes/{id}", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Khichdi"))
				.andExpect(jsonPath("$.categoryName").value("Rice"))
				.andExpect(jsonPath("$.ingredients.length()").value(2))
				.andExpect(jsonPath("$.version").value(1));

		assertThat(auditCount("RECIPE_CREATED")).isEqualTo(1);
	}

	@Test
	@DisplayName("a recipe naming garlic saves like any other, with no reason and no override (D-18)")
	void garlicIsAnOrdinaryIngredientNow() throws Exception {
		// The deliberate negative control for what this wave REMOVED, rather than for what it added.
		//
		// Until 2026-09-08 this exact call was refused with KMS-400037 for kitchen staff and for a
		// Temple Admin alike, and the only way past it was a Temple Admin supplying a written override
		// reason, which was then persisted on the recipe and audited as RECIPE_SATTVIC_OVERRIDDEN.
		// D-18 deleted the flag the block read, so the block, its escape hatch and its audit trail are
		// all gone. That is the accepted consequence of the ruling — it is asserted here on purpose so
		// that the next person to notice it finds a test saying "intended", not a defect report.
		//
		// Kitchen staff, deliberately: they were the role the block bit hardest, having no override.
		insertUser(templeA, "uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		signIn("uid-staff-a");
		UUID garlic = insertIngredient(templeA, "Garlic", "Vegetables");

		String body = ("{\"name\":\"Garlic Rice\",\"categoryId\":\"%s\",\"baseYieldQty\":10,"
				+ "\"baseYieldUnit\":\"KG\",\"ingredients\":"
				+ "[{\"ingredientId\":\"%s\",\"quantity\":1,\"unit\":\"KG\"}]}")
				.formatted(categoryRice, garlic);

		String response = mvc.perform(recipeRequest(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String id = response.replaceAll(".*\"id\"\\s*:\\s*\"([0-9a-f-]+)\".*", "$1");

		// No badge, no reason, no second audit event: the recipe is unremarkable, which is the point.
		mvc.perform(authed(get("/api/v1/recipes/{id}", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Garlic Rice"))
				.andExpect(jsonPath("$.sattvicOverrideReason").doesNotExist())
				.andExpect(jsonPath("$.ingredients[0].sattvicProhibited").doesNotExist());

		mvc.perform(authed(get("/api/v1/recipes")))
				.andExpect(jsonPath("$[?(@.name=='Garlic Rice')]").exists())
				.andExpect(jsonPath("$[0].sattvicOverridden").doesNotExist());

		assertThat(auditCount("RECIPE_CREATED")).isEqualTo(1);
		assertThat(auditCount("RECIPE_SATTVIC_OVERRIDDEN"))
				.as("the override audit action no longer exists, so nothing can record it")
				.isZero();
	}

	@Test
	@DisplayName("a recipe needs at least one ingredient")
	void rejectsEmptyIngredients() throws Exception {
		String body = ("{\"name\":\"Empty\",\"categoryId\":\"%s\",\"baseYieldQty\":10,"
				+ "\"baseYieldUnit\":\"KG\",\"ingredients\":[]}").formatted(categoryRice);
		mvc.perform(recipeRequest(body)).andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("a recipe cannot reference an ingredient from another temple")
	void rejectsCrossTenantIngredient() throws Exception {
		UUID otherIngredient = insertIngredient(templeB, "Ghee", "Dairy");

		String body = ("{\"name\":\"Sneaky\",\"categoryId\":\"%s\",\"baseYieldQty\":10,"
				+ "\"baseYieldUnit\":\"KG\",\"ingredients\":"
				+ "[{\"ingredientId\":\"%s\",\"quantity\":1,\"unit\":\"KG\"}]}")
				.formatted(categoryRice, otherIngredient);

		mvc.perform(recipeRequest(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));
	}

	@Test
	@DisplayName("browse can find recipes by a contained ingredient")
	void searchByContainedIngredient() throws Exception {
		createKhichdi(); // Rice + Dal
		String plainRice = ("{\"name\":\"Plain Rice\",\"categoryId\":\"%s\",\"baseYieldQty\":50,"
				+ "\"baseYieldUnit\":\"KG\",\"ingredients\":"
				+ "[{\"ingredientId\":\"%s\",\"quantity\":5,\"unit\":\"KG\"}]}").formatted(categoryRice, rice);
		mvc.perform(recipeRequest(plainRice)).andExpect(status().isCreated());

		mvc.perform(authed(get("/api/v1/recipes").param("ingredientId", dal.toString())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.name=='Khichdi')]").exists())
				.andExpect(jsonPath("$[?(@.name=='Plain Rice')]").doesNotExist());
	}

	@Test
	@DisplayName("editing replaces the lines and bumps the version")
	void updateReplacesLinesAndBumpsVersion() throws Exception {
		String id = createKhichdi();

		String update = ("{\"name\":\"Khichdi\",\"categoryId\":\"%s\",\"baseYieldQty\":120,"
				+ "\"baseYieldUnit\":\"KG\",\"ingredients\":"
				+ "[{\"ingredientId\":\"%s\",\"quantity\":3,\"unit\":\"KG\"}]}").formatted(categoryRice, rice);
		mvc.perform(authed(put("/api/v1/recipes/{id}", id))
				.contentType(MediaType.APPLICATION_JSON).content(update))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/recipes/{id}", id)))
				.andExpect(jsonPath("$.version").value(2))
				.andExpect(jsonPath("$.ingredients.length()").value(1))
				.andExpect(jsonPath("$.baseYieldQty").value(120));
	}

	@Test
	@DisplayName("a portion from another family of unit is refused on the portion, on create and on edit (T-218)")
	void refusesAPortionFromAnotherFamily() throws Exception {
		// Kilos of khichdi with a portion in millilitres: no density, so no head count, and the
		// planner could only guess. Refused as an ordinary field error on the portion unit.
		mvc.perform(recipeRequest(withPortion("KG", "200", "ML")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("perHeadUnit"))
				.andExpect(jsonPath("$.fieldErrors[0].message")
						.value("A recipe measured in Kg takes its portion in Kg or gm."));

		// Pieces are a family of one.
		mvc.perform(recipeRequest(withPortion("PIECES", "0.2", "KG")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors[0].field").value("perHeadUnit"))
				.andExpect(jsonPath("$.fieldErrors[0].message")
						.value("A recipe measured in pieces takes its portion in pieces."));

		// The same rule on the edit path, which is the one a stale browser tab would use.
		String id = createKhichdi();
		mvc.perform(authed(put("/api/v1/recipes/{id}", id))
				.contentType(MediaType.APPLICATION_JSON).content(withPortion("L", "350", "GM")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors[0].field").value("perHeadUnit"))
				.andExpect(jsonPath("$.fieldErrors[0].message")
						.value("A recipe measured in L takes its portion in L or ml."));
		assertThat(auditCount("RECIPE_UPDATED")).isZero();
	}

	@Test
	@DisplayName("a portion in the recipe's own family saves, and so does no portion at all (T-218)")
	void acceptsAPortionFromTheSameFamilyOrNone() throws Exception {
		// The recipe that started it: 270 L of rasam at 350 ml each.
		mvc.perform(recipeRequest(withPortion("L", "350", "ML").replace("Khichdi", "Rasam")))
				.andExpect(status().isCreated());
		mvc.perform(recipeRequest(withPortion("KG", "150", "GM").replace("Khichdi", "Pulao")))
				.andExpect(status().isCreated());
		// Blank stays allowed: the planner asks where a recipe states no portion.
		mvc.perform(recipeRequest(khichdiBody())).andExpect(status().isCreated());
		// An older recipe measured in grams still saves; the form only stopped offering grams.
		mvc.perform(recipeRequest(withPortion("GM", "50", "KG").replace("Khichdi", "Podi")))
				.andExpect(status().isCreated());
	}

	/** The khichdi body with a yield unit and a portion put in, for the family rule above. */
	private String withPortion(String yieldUnit, String perHeadQty, String perHeadUnit) {
		return ("{\"name\":\"Khichdi\",\"categoryId\":\"%s\",\"baseYieldQty\":270,"
				+ "\"baseYieldUnit\":\"%s\",\"perHeadQty\":%s,\"perHeadUnit\":\"%s\","
				+ "\"ingredients\":[{\"ingredientId\":\"%s\",\"quantity\":2,\"unit\":\"KG\"}]}")
				.formatted(categoryRice, yieldUnit, perHeadQty, perHeadUnit, rice);
	}

	@Test
	@DisplayName("archiving is a soft delete: gone from the default list, still fetchable")
	void archiveSoftDeletes() throws Exception {
		String id = createKhichdi();

		mvc.perform(authed(post("/api/v1/recipes/{id}/archive", id))).andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/recipes")))
				.andExpect(jsonPath("$[?(@.name=='Khichdi')]").doesNotExist());
		mvc.perform(authed(get("/api/v1/recipes").param("includeArchived", "true")))
				.andExpect(jsonPath("$[?(@.name=='Khichdi')]").exists());
		mvc.perform(authed(get("/api/v1/recipes/{id}", id)))
				.andExpect(jsonPath("$.status").value("ARCHIVED"));
	}

	@Test
	@DisplayName("archiving can be undone, so it is a decision rather than a one-way door")
	void archiveCanBeRestored() throws Exception {
		String id = createKhichdi();

		mvc.perform(authed(post("/api/v1/recipes/{id}/archive", id))).andExpect(status().isNoContent());
		mvc.perform(authed(post("/api/v1/recipes/{id}/restore", id))).andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/recipes/{id}", id)))
				.andExpect(jsonPath("$.status").value("ACTIVE"));
		mvc.perform(authed(get("/api/v1/recipes")))
				.andExpect(jsonPath("$[?(@.name=='Khichdi')]").exists());
	}

	@Test
	@DisplayName("a recipe nobody has cooked is deleted outright, and takes its lines with it")
	void neverCookedIsDeletedOutright() throws Exception {
		String id = createKhichdi();

		mvc.perform(authed(delete("/api/v1/recipes/{id}", id))).andExpect(status().isNoContent());

		// Gone, not hidden — a mistyped recipe should leave no trace to scroll past.
		mvc.perform(authed(get("/api/v1/recipes").param("includeArchived", "true")))
				.andExpect(jsonPath("$[?(@.name=='Khichdi')]").doesNotExist());
		mvc.perform(authed(get("/api/v1/recipes/{id}", id)))
				.andExpect(status().isNotFound());

		// The ingredient lines cascade; nothing is left pointing at a recipe that is not there.
		Integer lines = admin.queryForObject(
				"SELECT count(*) FROM recipe_ingredients WHERE recipe_id = ?::uuid", Integer.class, id);
		assertThat(lines).isZero();
	}

	@Test
	@DisplayName("a recipe that has been cooked refuses deletion and says to archive it")
	void cookedRecipeCannotBeDeleted() throws Exception {
		String id = createKhichdi();
		planAMealOf(id);

		// meal_dishes.recipe_id is ON DELETE RESTRICT precisely so the record of what was served
		// cannot be hollowed out. The refusal names the alternative rather than just saying no.
		mvc.perform(authed(delete("/api/v1/recipes/{id}", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400102"));

		mvc.perform(authed(get("/api/v1/recipes/{id}", id)))
				.andExpect(jsonPath("$.status").value("ACTIVE"));

		// And archiving, which is what it told the user to do, works.
		mvc.perform(authed(post("/api/v1/recipes/{id}/archive", id))).andExpect(status().isNoContent());
		mvc.perform(authed(get("/api/v1/recipes/{id}", id)))
				.andExpect(jsonPath("$.status").value("ARCHIVED"));
	}

	@Test
	@DisplayName("a duplicate active name is refused")
	void refusesDuplicateActiveName() throws Exception {
		createKhichdi();
		mvc.perform(recipeRequest(khichdiBody()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400036"));
	}

	@Test
	@DisplayName("scaling a recipe applies the ratio and promotes units on display")
	void scalesRecipe() throws Exception {
		String id = createKhichdi(); // base 100 servings: Rice 2 KG, Dal 1 KG

		// Halve it: ratio 0.5. Rice 2 KG -> 1 Kg; Dal 1 KG -> 0.5 Kg shown as 500 gm.
		mvc.perform(authed(get("/api/v1/recipes/{id}/scaled", id)).param("targetYield", "50"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ratio").value(0.5))
				.andExpect(jsonPath("$.ingredients[?(@.ingredientName=='Rice')].displayUnit").value("Kg"))
				.andExpect(jsonPath("$.ingredients[?(@.ingredientName=='Toor Dal')].displayUnit").value("gm"));
	}

	@Test
	@DisplayName("a line's preparation note is saved, read back, scaled and edited; a blank one is saved as none (R-DUP-1)")
	void preparationNoteTravelsWithTheLine() throws Exception {
		UUID chilli = insertIngredient(templeA, "Green chilli", "Vegetables");

		// Three lines: one with a note (spaces round it, which are not part of it), one with a
		// blank note, one with none at all. Rice twice, because the same ingredient prepared two
		// ways is exactly what the note is for.
		String body = ("{\"name\":\"Chilli Rice\",\"categoryId\":\"%s\",\"baseYieldQty\":100,"
				+ "\"baseYieldUnit\":\"KG\",\"ingredients\":["
				+ "{\"ingredientId\":\"%s\",\"quantity\":1,\"unit\":\"KG\",\"preparationNote\":\"  slit \"},"
				+ "{\"ingredientId\":\"%s\",\"quantity\":2,\"unit\":\"KG\",\"preparationNote\":\"   \"},"
				+ "{\"ingredientId\":\"%s\",\"quantity\":3,\"unit\":\"KG\"}]}")
				.formatted(categoryRice, chilli, rice, rice);
		String response = mvc.perform(recipeRequest(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String id = response.replaceAll(".*\"id\"\\s*:\\s*\"([0-9a-f-]+)\".*", "$1");

		// Stored trimmed, and blank stored as null: the column's not-blank check (V144) would refuse
		// the whitespace outright, so this proves the service cleans it rather than the database
		// rejecting it.
		assertThat(admin.queryForList(
				"SELECT preparation_note FROM recipe_ingredients WHERE recipe_id = ?::uuid ORDER BY line_order",
				String.class, id)).containsExactly("slit", null, null);

		mvc.perform(authed(get("/api/v1/recipes/{id}", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ingredients[0].ingredientName").value("Green chilli"))
				.andExpect(jsonPath("$.ingredients[0].preparationNote").value("slit"))
				.andExpect(jsonPath("$.ingredients[1].preparationNote").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.ingredients[2].preparationNote").value(org.hamcrest.Matchers.nullValue()));

		mvc.perform(authed(get("/api/v1/recipes/{id}/scaled", id)).param("targetYield", "200"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ingredients[0].ingredientName").value("Green chilli"))
				.andExpect(jsonPath("$.ingredients[0].preparationNote").value("slit"))
				.andExpect(jsonPath("$.ingredients[0].rawQuantity").value(2));

		// An edit replaces the lines, notes and all.
		String update = ("{\"name\":\"Chilli Rice\",\"categoryId\":\"%s\",\"baseYieldQty\":100,"
				+ "\"baseYieldUnit\":\"KG\",\"ingredients\":["
				+ "{\"ingredientId\":\"%s\",\"quantity\":1,\"unit\":\"KG\",\"preparationNote\":\"chopped fine\"},"
				+ "{\"ingredientId\":\"%s\",\"quantity\":2,\"unit\":\"KG\",\"preparationNote\":null}]}")
				.formatted(categoryRice, chilli, rice);
		mvc.perform(authed(put("/api/v1/recipes/{id}", id))
				.contentType(MediaType.APPLICATION_JSON).content(update))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/recipes/{id}", id)))
				.andExpect(jsonPath("$.ingredients.length()").value(2))
				.andExpect(jsonPath("$.ingredients[0].preparationNote").value("chopped fine"))
				.andExpect(jsonPath("$.ingredients[1].preparationNote").value(org.hamcrest.Matchers.nullValue()));
	}

	@Test
	@DisplayName("scaling refuses a non-positive or absurd target yield")
	void rejectsBadTargetYield() throws Exception {
		String id = createKhichdi();
		mvc.perform(authed(get("/api/v1/recipes/{id}/scaled", id)).param("targetYield", "0"))
				.andExpect(status().isBadRequest());
		mvc.perform(authed(get("/api/v1/recipes/{id}/scaled", id)).param("targetYield", "60000"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("the seeded category list is readable")
	void listsCategories() throws Exception {
		mvc.perform(authed(get("/api/v1/recipe-categories")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.name=='Rice')]").exists());
	}

	// ---------------------------------------------------------------------

	/**
	 * Puts the recipe on a meal plan, which is the only thing that makes deleting it wrong — and it
	 * is written straight to the table rather than through the planner, because the point here is
	 * the reference, not the planning.
	 */
	private void planAMealOf(String recipeId) {
		MealFixture.plan(admin, templeA, java.time.LocalDate.now(), "Lunch", java.time.LocalTime.NOON,
				UUID.fromString(recipeId), java.math.BigDecimal.valueOf(100), "PLANNED",
				admin.queryForObject("SELECT id FROM users WHERE firebase_uid = 'uid-admin-a'", UUID.class));
	}

	private String createKhichdi() throws Exception {
		String response = mvc.perform(recipeRequest(khichdiBody()))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return response.replaceAll(".*\"id\"\\s*:\\s*\"([0-9a-f-]+)\".*", "$1");
	}

	private String khichdiBody() {
		return ("{\"name\":\"Khichdi\",\"categoryId\":\"%s\",\"baseYieldQty\":100,"
				+ "\"baseYieldUnit\":\"KG\",\"method\":\"Cook rice and dal together.\","
				+ "\"ingredients\":[{\"ingredientId\":\"%s\",\"quantity\":2,\"unit\":\"KG\"},"
				+ "{\"ingredientId\":\"%s\",\"quantity\":1,\"unit\":\"KG\"}]}")
				.formatted(categoryRice, rice, dal);
	}

	private MockHttpServletRequestBuilder recipeRequest(String json) {
		return authed(post("/api/v1/recipes")).contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private int auditCount(String action) {
		Integer c = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = ?", Integer.class, action);
		return c == null ? 0 : c;
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

	private void insertUser(UUID tenantId, String uid, String email, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				""", tenantId, uid, email, role);
	}

	private UUID insertCategory(UUID tenantId, String name, boolean fasting) {
		return admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name, fasting_compatible)
				VALUES (?, ?, ?) RETURNING id
				""", UUID.class, tenantId, name, fasting);
	}

	private UUID insertIngredient(UUID tenantId, String name, String category) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, ?, 'KG') RETURNING id
				""", UUID.class, tenantId, name, category);
	}

}
