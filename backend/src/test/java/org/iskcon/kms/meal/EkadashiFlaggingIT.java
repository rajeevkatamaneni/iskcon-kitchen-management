package org.iskcon.kms.meal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.calendar.CalendarService;
import org.iskcon.kms.meal.MealKindService;
import org.iskcon.kms.tenancy.TenantContext;
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
 * Ekadashi violation flagging (E4-S6): a grain/bean recipe on Ekadashi warns and requires explicit
 * acknowledgment; an Ekadashi-friendly recipe doesn't; a calendar override changes the flagging; and
 * the ingredient flag is Temple-Admin only.
 */
@AutoConfigureMockMvc
@Import(EkadashiFlaggingIT.StubVerifierConfiguration.class)
class EkadashiFlaggingIT extends AbstractIntegrationTest {

	private static final String EKADASHI = "2025-01-10";   // computed Ekadashi (Bengaluru)
	private static final String ORDINARY = "2025-01-13";   // a Monday, not Ekadashi

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private MealKindService mealKindService;

	@Autowired
	private CalendarService calendarService;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID rice;
	private UUID khichdi;
	private UUID kheer;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		insertUser("uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");
		insertUser("uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");

		rice = ingredient("Rice", "Grains", true);
		UUID dal = ingredient("Toor Dal", "Pulses", true);
		UUID milk = ingredient("Milk", "Dairy", false);
		UUID sugar = ingredient("Sugar", "Other", false);
		khichdi = recipe("Khichdi", "Rice");
		line(khichdi, rice, "5");
		line(khichdi, dal, "2");
		kheer = recipe("Kheer", "Sweets");
		line(kheer, milk, "10");
		line(kheer, sugar, "2");

		TenantContext.set(tenant);
		try {
			mealKindService.seedForCurrentTenant();
			calendarService.precomputeForCurrentTenant(LocalDate.of(2025, 1, 1), 40);
		} finally {
			TenantContext.clear();
		}
		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM meal_plans");
		admin.execute("DELETE FROM meal_kinds");
		admin.execute("DELETE FROM calendar_overrides");
		admin.execute("DELETE FROM calendar_days");
		admin.execute("DELETE FROM calendar_precompute_state");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("the check flags a grain recipe on Ekadashi and clears an Ekadashi-friendly one")
	void checkFlagsGrainRecipe() throws Exception {
		mvc.perform(check(EKADASHI, khichdi))
				.andExpect(jsonPath("$.isEkadashi").value(true))
				.andExpect(jsonPath("$.compatible").value(false))
				.andExpect(jsonPath("$.offendingIngredients").isArray())
				.andExpect(jsonPath("$.offendingIngredients[?(@=='Rice')]").exists())
				.andExpect(jsonPath("$.offendingIngredients[?(@=='Toor Dal')]").exists());

		mvc.perform(check(EKADASHI, kheer))
				.andExpect(jsonPath("$.isEkadashi").value(true))
				.andExpect(jsonPath("$.compatible").value(true));

		mvc.perform(check(ORDINARY, khichdi))
				.andExpect(jsonPath("$.isEkadashi").value(false));
	}

	@Test
	@DisplayName("planning a grain recipe on Ekadashi is blocked until acknowledged, then recorded")
	void grainOnEkadashiNeedsAck() throws Exception {
		mvc.perform(plan(EKADASHI, khichdi, false))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4917"));

		UUID id = created(plan(EKADASHI, khichdi, true));
		mvc.perform(get("/api/v1/meal-plans/{id}", id).header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.ekadashiAcknowledged").value(true));
	}

	@Test
	@DisplayName("an Ekadashi-friendly recipe needs no acknowledgment; a grain recipe off Ekadashi is fine")
	void compatibleAndOrdinaryNeedNoAck() throws Exception {
		mvc.perform(plan(EKADASHI, kheer, false)).andExpect(status().isCreated());
		mvc.perform(plan(ORDINARY, khichdi, false)).andExpect(status().isCreated());
	}

	@Test
	@DisplayName("a calendar override flipping Ekadashi status changes the flagging")
	void overrideChangesFlagging() throws Exception {
		// Ordinary day, grain recipe — normally fine.
		mvc.perform(check(ORDINARY, khichdi)).andExpect(jsonPath("$.isEkadashi").value(false));

		// Admin overrides that date to Ekadashi.
		mvc.perform(put("/api/v1/calendar/{d}/override", ORDINARY)
						.header("Authorization", "Bearer valid-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"isEkadashi\":true,\"reason\":\"GBC ruling\"}"))
				.andExpect(status().isNoContent());

		mvc.perform(check(ORDINARY, khichdi)).andExpect(jsonPath("$.isEkadashi").value(true));
		mvc.perform(plan(ORDINARY, khichdi, false))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4917"));
	}

	@Test
	@DisplayName("only a Temple Admin can change the Ekadashi-prohibited flag")
	void onlyAdminSetsIngredientFlag() throws Exception {
		signIn("uid-staff-a");
		mvc.perform(patch("/api/v1/ingredients/{id}/ekadashi-flag", rice)
						.header("Authorization", "Bearer valid-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ekadashiProhibited\":false}"))
				.andExpect(status().isForbidden());

		signIn("uid-admin-a");
		mvc.perform(patch("/api/v1/ingredients/{id}/ekadashi-flag", rice)
						.header("Authorization", "Bearer valid-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ekadashiProhibited\":false}"))
				.andExpect(status().isNoContent());

		// Rice no longer prohibited → Khichdi becomes compatible.
		mvc.perform(check(EKADASHI, khichdi))
				.andExpect(jsonPath("$.offendingIngredients[?(@=='Rice')]").doesNotExist());
	}

	@Test
	@DisplayName("the recipe picker can filter to Ekadashi-compatible recipes")
	void pickerFiltersCompatible() throws Exception {
		mvc.perform(get("/api/v1/recipes").param("ekadashiCompatible", "true")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$[?(@.name=='Kheer')]").exists())
				.andExpect(jsonPath("$[?(@.name=='Khichdi')]").doesNotExist());
	}

	// ---------------------------------------------------------------------

	private MockHttpServletRequestBuilder check(String date, UUID recipeId) {
		return get("/api/v1/meal-plans/ekadashi-check").param("date", date)
				.param("recipeId", recipeId.toString())
				.header("Authorization", "Bearer valid-token");
	}

	private MockHttpServletRequestBuilder plan(String date, UUID recipeId, boolean ack) {
		return post("/api/v1/meal-plans").header("Authorization", "Bearer valid-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"planDate\":\"" + date + "\",\"mealKind\":\"Lunch\",\"recipeId\":\"" + recipeId
						+ "\",\"targetYield\":100,\"dayType\":\"REGULAR\",\"ekadashiAcknowledged\":" + ack + "}");
	}

	private UUID created(MockHttpServletRequestBuilder req) throws Exception {
		String body = mvc.perform(req).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
	}

	private UUID ingredient(String name, String category, boolean ekadashiProhibited) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit, is_ekadashi_prohibited)
				VALUES (?, ?, ?, 'KG', ?) RETURNING id
				""", UUID.class, tenant, name, category, ekadashiProhibited);
	}

	private UUID recipe(String name, String categoryName) {
		UUID cat = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, ?) RETURNING id
				""", UUID.class, tenant, categoryName);
		return admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, ?, ?, 100, 'SERVINGS') RETURNING id
				""", UUID.class, tenant, name, cat);
	}

	private void line(UUID recipe, UUID ingredient, String qty) {
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, ?::numeric, 'KG', 0)
				""", tenant, recipe, ingredient, qty);
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private void insertUser(String uid, String email, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				""", tenant, uid, email, role);
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
