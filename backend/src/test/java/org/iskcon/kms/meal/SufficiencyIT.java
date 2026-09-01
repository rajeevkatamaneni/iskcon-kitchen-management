package org.iskcon.kms.meal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
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

/**
 * Ingredient sufficiency and the shortfall feed (E4-S5): the double-booking guard (two meals can't
 * both claim one sack), badge transitions when stock arrives, and the aggregate shortfall contract.
 */
@AutoConfigureMockMvc
@Import(SufficiencyIT.StubVerifierConfiguration.class)
class SufficiencyIT extends AbstractIntegrationTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private MealKindService mealKindService;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID rice;
	private UUID khichdi;
	private final LocalDate d1 = LocalDate.now(IST).plusDays(2);
	private final LocalDate d2 = LocalDate.now(IST).plusDays(3);

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
		insertUser("uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		insertUser("uid-vol-a", "vol-a@example.com", "VOLUNTEER");

		rice = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Rice', 'Grains', 'KG') RETURNING id
				""", UUID.class, tenant);
		UUID cat = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Rice') RETURNING id
				""", UUID.class, tenant);
		khichdi = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, 'Khichdi', ?, 100, 'KG') RETURNING id
				""", UUID.class, tenant, cat);
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, 5, 'KG', 0)
				""", tenant, khichdi, rice);
		seedReceipt("7"); // 7 KG — enough for one 100-serving Khichdi (5 KG), not two

		TenantContext.set(tenant);
		try {
			mealKindService.seedForCurrentTenant();
		} finally {
			TenantContext.clear();
		}
		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM meal_plans");
		admin.execute("DELETE FROM meal_kinds");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM audit_events");
		// Anything that moved through the stock ledger is tracked now, so the item rows exist
		// even where the test never asked for them, and they hold the ingredient down.
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("two meals can't both claim one sack; stock arriving clears the shortfall")
	void doubleBookingGuardAndTransition() throws Exception {
		plan(d1);
		plan(d2);

		String from = LocalDate.now(IST).toString();
		String to = LocalDate.now(IST).plusDays(5).toString();

		// First meal covered, second short by 3 KG (needs 5, only 2 left).
		mvc.perform(get("/api/v1/meal-plans/sufficiency").param("from", from).param("to", to)
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].status").value("SUFFICIENT"))
				.andExpect(jsonPath("$[1].status").value("SHORT"))
				.andExpect(jsonPath("$[1].shortfalls[0].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[1].shortfalls[0].shortBy").value(3))
				.andExpect(jsonPath("$[1].shortfalls[0].available").value(2));

		// A 10 KG delivery arrives — now both are covered.
		seedReceipt("10");
		mvc.perform(get("/api/v1/meal-plans/sufficiency").param("from", from).param("to", to)
						.header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$[0].status").value("SUFFICIENT"))
				.andExpect(jsonPath("$[1].status").value("SUFFICIENT"));
	}

	@Test
	@DisplayName("the shortfall feed aggregates the exact quantity the ordering pipeline needs")
	void shortfallFeedContract() throws Exception {
		plan(d1);
		plan(d2);

		mvc.perform(get("/api/v1/meal-plans/shortfall").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[0].shortBy").value(3))
				// Both are data rather than display: the figure is the exact one the ordering
				// pipeline buys against, and the unit is the stored name, as every `unit` field in
				// this API is. Formatting happens where a person reads it (E11-S5).
				.andExpect(jsonPath("$[0].unit").value("KG"));
	}

	@Test
	@DisplayName("a volunteer cannot read sufficiency")
	void volunteerForbidden() throws Exception {
		signIn("uid-vol-a");
		mvc.perform(get("/api/v1/meal-plans/sufficiency")
						.param("from", d1.toString()).param("to", d2.toString())
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private void plan(LocalDate date) throws Exception {
		mvc.perform(post("/api/v1/meal-plans").header("Authorization", "Bearer valid-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"planDate\":\"" + date + "\",\"mealKind\":\"Lunch\",\"recipeId\":\"" + khichdi
								+ "\",\"targetYield\":100,\"adults\":100,\"dayType\":\"REGULAR\"}"))
				.andExpect(status().isCreated());
	}

	private void seedReceipt(String qtyKg) {
		admin.update("""
				INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit,
						movement_type, actor_user_id)
				VALUES (?, ?, ?, ?::numeric, 'KG', 'PO_RECEIPT',
						(SELECT id FROM users WHERE firebase_uid = 'uid-staff-a'))
				""", tenant, rice, UUID.randomUUID(), qtyKg);
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
