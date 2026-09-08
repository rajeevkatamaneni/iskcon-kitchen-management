package org.iskcon.kms.ingredient;

import static org.assertj.core.api.Assertions.assertThat;
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

/**
 * Supplies as a flag on the ingredient catalogue (T-023, D-1), through the full stack.
 *
 * <p>D-1 rejected a parallel {@code supply_items} table with a stock ledger of its own, because LPG,
 * leaf plates, dishwashing liquid and hand soap are bought, received, stored, used up and reordered
 * exactly as food is. So the interesting assertions here are mostly about what did <em>not</em>
 * change: a supply is listed with everything else, and it starts being tracked in inventory through
 * the same endpoint food does. The single behavioural difference is a recipe, and it is asserted
 * both ways — a supply is refused, food beside it is not.
 *
 * <p>Deliberately not asserted: "the backfill ran per tenant under RLS". V99 adds the column as
 * {@code NOT NULL DEFAULT false}, which is DDL run as the table owner; PostgreSQL fills every
 * existing row itself without a row policy ever being consulted, so there is no backfill statement
 * and no per-tenant loop to test. What is asserted instead, in
 * {@link #everyExistingIngredientIsFood}, is the fact that criterion was reaching for: rows written
 * before the column existed read as food, including a row in a tenant nobody adopted.
 */
@AutoConfigureMockMvc
@Import(SupplyIngredientIT.StubVerifierConfiguration.class)
class SupplyIngredientIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID templeB;
	private UUID categoryRice;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
		insertUser(templeA, "uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		categoryRice = insertCategory(templeA, "Rice");
		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM meal_plans");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a supply is created through the ordinary ingredient endpoint and listed with food")
	void createsASupplyAndListsItWithFood() throws Exception {
		mvc.perform(createRequest("{\"name\":\"Leaf Plates\",\"category\":\"Disposables\","
						+ "\"unit\":\"PIECES\",\"ekadashiProhibited\":false,\"supply\":true}"))
				.andExpect(status().isCreated());
		mvc.perform(createRequest("{\"name\":\"Rice\",\"category\":\"Grains\",\"unit\":\"KG\","
						+ "\"ekadashiProhibited\":false,\"supply\":false}"))
				.andExpect(status().isCreated());

		// One catalogue, one endpoint, both kinds of thing on it. Every picker in the application
		// reads this list, which is exactly why only the recipe picker filters.
		mvc.perform(authed(get("/api/v1/ingredients")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[?(@.name=='Leaf Plates')].supply").value(true))
				.andExpect(jsonPath("$[?(@.name=='Rice')].supply").value(false));

		assertThat(admin.queryForObject(
				"SELECT is_supply FROM ingredients WHERE name = 'Leaf Plates'", Boolean.class))
				.isTrue();
	}

	@Test
	@DisplayName("kitchen staff may flag a supply — it is a catalogue fact, not a religious one")
	void supplyNeedsNoSecondPermission() throws Exception {
		// The point of contrast is the test next door in IngredientIT: the same role posting
		// ekadashiProhibited:true is refused KMS-400021, because that IS a religious-policy call.
		// Saying a thing is a mop is not, so no separate permission was invented for it (D-1).
		signIn("uid-staff-a");

		mvc.perform(createRequest("{\"name\":\"Dishwashing Liquid\",\"category\":\"Cleaning\","
						+ "\"unit\":\"L\",\"supply\":true}"))
				.andExpect(status().isCreated());

		assertThat(admin.queryForObject(
				"SELECT is_supply FROM ingredients WHERE name = 'Dishwashing Liquid'", Boolean.class))
				.isTrue();
	}

	@Test
	@DisplayName("a thing can stop being a supply, and start being one, through an ordinary edit")
	void theFlagIsEditableBothWays() throws Exception {
		UUID lpg = insertIngredient(templeA, "LPG Cylinder", "Fuel", true);

		// A temple that catalogued its cylinders wrongly fixes it here, with no endpoint of its own.
		mvc.perform(updateRequest(lpg, "{\"name\":\"LPG Cylinder\",\"category\":\"Fuel\","
						+ "\"unit\":\"PIECES\",\"supply\":false,\"aliases\":[]}"))
				.andExpect(status().isNoContent());
		assertThat(flagOf(lpg)).isFalse();

		mvc.perform(updateRequest(lpg, "{\"name\":\"LPG Cylinder\",\"category\":\"Fuel\","
						+ "\"unit\":\"PIECES\",\"supply\":true,\"aliases\":[]}"))
				.andExpect(status().isNoContent());
		assertThat(flagOf(lpg)).isTrue();

		// The audit trail carries the change, because the whole row's before/after is snapshotted.
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM audit_events
				WHERE action = 'INGREDIENT_UPDATED' AND after_state ->> 'supply' = 'true'
				""", Integer.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("an edit that omits the flag turns a supply back into food — the trap, asserted")
	void anEditThatOmitsTheFlagIsTheKnownTrap() throws Exception {
		// `supply` is a primitive on the request record, so an absent JSON key deserialises to
		// false — the permissive answer, silently. This asserts the trap exists rather than
		// pretending it does not, because it is the whole reason the CLIENT type declares `supply`
		// required rather than optional and the edit row always sends the value it is showing.
		//
		// If this test ever turns red, someone has made the field a Boolean or added a default, and
		// the client-side reasoning in api.ts needs rereading before the change is accepted.
		UUID soap = insertIngredient(templeA, "Hand Soap", "Hygiene", true);

		mvc.perform(updateRequest(soap, "{\"name\":\"Hand Soap\",\"category\":\"Hygiene\","
						+ "\"unit\":\"L\",\"aliases\":[]}"))
				.andExpect(status().isNoContent());

		assertThat(flagOf(soap))
				.as("an omitted primitive reads as false; the client is what must not omit it")
				.isFalse();
	}

	@Test
	@DisplayName("a supply starts being tracked in inventory exactly as food does")
	void aSupplyGoesIntoInventory() throws Exception {
		// The load-bearing half of D-1: no parallel table, so the inventory chain takes a supply
		// with no special case at all. Nothing here mentions the flag; that is the assertion.
		UUID plates = insertIngredient(templeA, "Leaf Plates", "Disposables", true);

		mvc.perform(authed(post("/api/v1/inventory/items"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ingredientId\":\"" + plates + "\",\"storageLocation\":\"Store room\","
								+ "\"reorderThreshold\":500}"))
				.andExpect(status().isCreated());

		mvc.perform(authed(get("/api/v1/inventory/items")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.ingredientName=='Leaf Plates')]").exists());
	}

	@Test
	@DisplayName("a supply on a recipe line is refused with KMS-400127, and food beside it is not")
	void aRecipeRefusesASupply() throws Exception {
		UUID rice = insertIngredient(templeA, "Rice", "Grains", false);
		UUID plates = insertIngredient(templeA, "Leaf Plates", "Disposables", true);

		// The picker hides supplies, but a picker is not a guard — this is the raw POST it cannot
		// see. Rice is on the same request, so what is being refused is the supply and not the call.
		mvc.perform(recipeRequest(("{\"name\":\"Plated Rice\",\"categoryId\":\"%s\","
						+ "\"baseYieldQty\":100,\"baseYieldUnit\":\"KG\",\"ingredients\":["
						+ "{\"ingredientId\":\"%s\",\"quantity\":20,\"unit\":\"KG\"},"
						+ "{\"ingredientId\":\"%s\",\"quantity\":300,\"unit\":\"PIECES\"}]}")
						.formatted(categoryRice, rice, plates)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400127"));

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM recipes", Integer.class))
				.as("nothing was written before the refusal")
				.isZero();

		// The same call without the leaf plates is ordinary work, which is what makes the refusal
		// about the supply rather than about the shape of the request.
		mvc.perform(recipeRequest(("{\"name\":\"Plain Rice\",\"categoryId\":\"%s\","
						+ "\"baseYieldQty\":100,\"baseYieldUnit\":\"KG\",\"ingredients\":["
						+ "{\"ingredientId\":\"%s\",\"quantity\":20,\"unit\":\"KG\"}]}")
						.formatted(categoryRice, rice)))
				.andExpect(status().isCreated());
	}

	@Test
	@DisplayName("editing a recipe onto a supply is refused too, not only creating one")
	void editingARecipeOntoASupplyIsRefused() throws Exception {
		UUID rice = insertIngredient(templeA, "Rice", "Grains", false);
		UUID plates = insertIngredient(templeA, "Leaf Plates", "Disposables", true);

		String created = mvc.perform(recipeRequest(("{\"name\":\"Plain Rice\",\"categoryId\":\"%s\","
						+ "\"baseYieldQty\":100,\"baseYieldUnit\":\"KG\",\"ingredients\":["
						+ "{\"ingredientId\":\"%s\",\"quantity\":20,\"unit\":\"KG\"}]}")
						.formatted(categoryRice, rice)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String id = created.replaceAll(".*\"id\"\\s*:\\s*\"([0-9a-f-]+)\".*", "$1");

		mvc.perform(authed(put("/api/v1/recipes/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content(("{\"name\":\"Plain Rice\",\"categoryId\":\"%s\",\"baseYieldQty\":100,"
								+ "\"baseYieldUnit\":\"KG\",\"ingredients\":["
								+ "{\"ingredientId\":\"%s\",\"quantity\":300,\"unit\":\"PIECES\"}]}")
								.formatted(categoryRice, plates)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400127"));

		// The recipe still holds the rice line it had: a refused edit changes nothing.
		mvc.perform(authed(get("/api/v1/recipes/{id}", id)))
				.andExpect(jsonPath("$.ingredients.length()").value(1))
				.andExpect(jsonPath("$.ingredients[0].ingredientName").value("Rice"));
	}

	@Test
	@DisplayName("every ingredient that existed before the column does reads as food, in every tenant")
	void everyExistingIngredientIsFood() throws Exception {
		// V99's column arrived NOT NULL DEFAULT false, so this is what "the migration made existing
		// ingredients food" actually means: rows written by an INSERT that never names is_supply —
		// which is every row that predates the column, and also every row recipe import writes —
		// come out false.
		//
		// Temple B's row is here on purpose. It belongs to a tenant nobody signs in as, so nothing
		// in this test ever adopts its tenant context; if the column had been filled by DML under
		// RLS instead of by DDL, a row like this is precisely the one that would have been missed.
		UUID riceA = insertIngredientWithoutTheColumn(templeA, "Rice", "Grains");
		UUID payasamB = insertIngredientWithoutTheColumn(templeB, "Payasam Base", "Sweets");

		assertThat(flagOf(riceA)).isFalse();
		assertThat(flagOf(payasamB)).isFalse();
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM ingredients WHERE is_supply", Integer.class)).isZero();

		mvc.perform(authed(get("/api/v1/ingredients")))
				.andExpect(jsonPath("$[?(@.name=='Rice')].supply").value(false));
	}

	@Test
	@DisplayName("the typeahead carries the flag, so a picker reading it can honour it")
	void typeaheadCarriesTheFlag() throws Exception {
		insertIngredient(templeA, "Leaf Plates", "Disposables", true);

		mvc.perform(authed(get("/api/v1/ingredients/search").param("q", "leaf")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.name=='Leaf Plates')].supply").value(true));
	}

	// ---------------------------------------------------------------------

	private Boolean flagOf(UUID id) {
		return admin.queryForObject("SELECT is_supply FROM ingredients WHERE id = ?", Boolean.class, id);
	}

	private UUID insertIngredient(UUID tenantId, String name, String category, boolean supply) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit, is_supply)
				VALUES (?, ?, ?, 'KG', ?) RETURNING id
				""", UUID.class, tenantId, name, category, supply);
	}

	/** An insert that never names the column, which is how every row older than V99 was written. */
	private UUID insertIngredientWithoutTheColumn(UUID tenantId, String name, String category) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, ?, 'KG') RETURNING id
				""", UUID.class, tenantId, name, category);
	}

	private UUID insertCategory(UUID tenantId, String name) {
		return admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name, fasting_compatible)
				VALUES (?, ?, false) RETURNING id
				""", UUID.class, tenantId, name);
	}

	private MockHttpServletRequestBuilder createRequest(String json) {
		return authed(post("/api/v1/ingredients")).contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder updateRequest(UUID id, String json) {
		return authed(put("/api/v1/ingredients/{id}", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content(json);
	}

	private MockHttpServletRequestBuilder recipeRequest(String json) {
		return authed(post("/api/v1/recipes")).contentType(MediaType.APPLICATION_JSON).content(json);
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
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);
	}

	private void insertUser(UUID tenantId, String uid, String email, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				""", tenantId, uid, email, role);
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
