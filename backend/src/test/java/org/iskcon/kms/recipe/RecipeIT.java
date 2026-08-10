package org.iskcon.kms.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Recipe CRUD (E2-S2) through the full stack: RLS, cross-tenant reference rejection, search, archive. */
@AutoConfigureMockMvc
@Import(RecipeIT.StubVerifierConfiguration.class)
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
		stubVerifier.reset();
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
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
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
	@DisplayName("a recipe needs at least one ingredient")
	void rejectsEmptyIngredients() throws Exception {
		String body = ("{\"name\":\"Empty\",\"categoryId\":\"%s\",\"baseYieldQty\":10,"
				+ "\"baseYieldUnit\":\"SERVINGS\",\"ingredients\":[]}").formatted(categoryRice);
		mvc.perform(recipeRequest(body)).andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("a recipe cannot reference an ingredient from another temple")
	void rejectsCrossTenantIngredient() throws Exception {
		UUID otherIngredient = insertIngredient(templeB, "Ghee", "Dairy");

		String body = ("{\"name\":\"Sneaky\",\"categoryId\":\"%s\",\"baseYieldQty\":10,"
				+ "\"baseYieldUnit\":\"SERVINGS\",\"ingredients\":"
				+ "[{\"ingredientId\":\"%s\",\"quantity\":1,\"unit\":\"KG\"}]}")
				.formatted(categoryRice, otherIngredient);

		mvc.perform(recipeRequest(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4001"));
	}

	@Test
	@DisplayName("browse can find recipes by a contained ingredient")
	void searchByContainedIngredient() throws Exception {
		createKhichdi(); // Rice + Dal
		String plainRice = ("{\"name\":\"Plain Rice\",\"categoryId\":\"%s\",\"baseYieldQty\":50,"
				+ "\"baseYieldUnit\":\"SERVINGS\",\"ingredients\":"
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
				+ "\"baseYieldUnit\":\"SERVINGS\",\"ingredients\":"
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
	@DisplayName("archiving is a soft delete: gone from the default list, still fetchable")
	void archiveSoftDeletes() throws Exception {
		String id = createKhichdi();

		mvc.perform(authed(delete("/api/v1/recipes/{id}", id))).andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/recipes")))
				.andExpect(jsonPath("$[?(@.name=='Khichdi')]").doesNotExist());
		mvc.perform(authed(get("/api/v1/recipes").param("includeArchived", "true")))
				.andExpect(jsonPath("$[?(@.name=='Khichdi')]").exists());
		mvc.perform(authed(get("/api/v1/recipes/{id}", id)))
				.andExpect(jsonPath("$.status").value("ARCHIVED"));
	}

	@Test
	@DisplayName("a duplicate active name is refused")
	void refusesDuplicateActiveName() throws Exception {
		createKhichdi();
		mvc.perform(recipeRequest(khichdiBody()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4905"));
	}

	@Test
	@DisplayName("the seeded category list is readable")
	void listsCategories() throws Exception {
		mvc.perform(authed(get("/api/v1/recipe-categories")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.name=='Rice')]").exists());
	}

	// ---------------------------------------------------------------------

	private String createKhichdi() throws Exception {
		String response = mvc.perform(recipeRequest(khichdiBody()))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return response.replaceAll(".*\"id\"\\s*:\\s*\"([0-9a-f-]+)\".*", "$1");
	}

	private String khichdiBody() {
		return ("{\"name\":\"Khichdi\",\"categoryId\":\"%s\",\"baseYieldQty\":100,"
				+ "\"baseYieldUnit\":\"SERVINGS\",\"method\":\"Cook rice and dal together.\","
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

	// ---------------------------------------------------------------------

	@TestConfiguration
	static class StubVerifierConfiguration {

		@Bean
		@Primary
		StubTokenVerifier stubTokenVerifier() {
			return new StubTokenVerifier();
		}
	}

	static class StubTokenVerifier implements TokenVerifier {

		private final Map<String, VerifiedSubject> accepted = new HashMap<>();

		void accept(String uid) {
			accepted.put("valid-token", new VerifiedSubject(uid, uid + "@example.com", "+919000000000"));
		}

		void reset() {
			accepted.clear();
		}

		@Override
		public VerifiedSubject verify(String idToken) throws InvalidTokenException {
			VerifiedSubject subject = accepted.get(idToken);
			if (subject == null) {
				throw new InvalidTokenException("Unrecognised token");
			}
			return subject;
		}
	}
}
