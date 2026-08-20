package org.iskcon.kms.costing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The estimated cost of a day's food (B2): that it prices from the preferred vendor rather than
 * whoever else supplies the same thing, that an ingredient nobody has priced is counted and named
 * instead of costed at zero, and that the day's figure survives the kitchen actually cooking.
 *
 * <p>The fixture is one day of two dishes:
 *
 * <ul>
 *   <li>Khichdi for 200, from a 100-serving recipe — 10 Kg rice, 4 Kg dal, 400 gm rock salt.
 *   <li>Payasam for 100, from a 50-serving recipe — 6 Kg rice.
 * </ul>
 *
 * <p>which is 16 Kg of rice at the preferred vendor's ₹45 and 4 Kg of dal at ₹120 (the dearest of
 * two unpreferred vendors), or ₹1,200 — with the rock salt, which no vendor supplies, left out and
 * declared.
 */
@AutoConfigureMockMvc
@Import(MaterialsCostIT.StubVerifierConfiguration.class)
class MaterialsCostIT extends AbstractIntegrationTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID khichdi;
	private UUID payasam;
	private final LocalDate day = LocalDate.now(IST).plusDays(1);

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

		UUID rice = ingredient("Rice");
		UUID dal = ingredient("Toor Dal");
		UUID salt = ingredient("Rock Salt");

		UUID preferredVendor = vendor("Govind Wholesale");
		UUID otherVendor = vendor("Sri Traders");
		// Rice: the temple has named who it buys from, so the other vendor's dearer price is ignored.
		supply(preferredVendor, rice, "45.00", true);
		supply(otherVendor, rice, "90.00", false);
		// Dal: no preference recorded, so the estimate takes the dearer of the two rather than
		// flattering the budget with the cheaper.
		supply(preferredVendor, dal, "100.00", false);
		supply(otherVendor, dal, "120.00", false);
		// Rock salt is supplied by nobody, and is the hole this figure has to admit to.

		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Rice') RETURNING id
				""", UUID.class, tenant);
		khichdi = recipe("Khichdi", category, 100);
		line(khichdi, rice, "5", "KG", 0);
		line(khichdi, dal, "2", "KG", 1);
		// Deliberately in grammes against an ingredient held in Kg: same family, so it converts, and
		// the cost must not be out by a factor of a thousand.
		line(khichdi, salt, "200", "GM", 2);
		payasam = recipe("Payasam", category, 50);
		line(payasam, rice, "3", "KG", 0);

		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM meal_plans");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("two meals in a day are priced at the preferred vendor's last-known price")
	void pricesTheDayFromThePreferredVendor() throws Exception {
		plan(khichdi, "200", "PLANNED");
		plan(payasam, "100", "PLANNED");

		cost().andExpect(status().isOk())
				.andExpect(jsonPath("$.date").value(day.toString()))
				.andExpect(jsonPath("$.estimatedTotal").value(1200.00))
				.andExpect(jsonPath("$.ingredientsPriced").value(2));
	}

	@Test
	@DisplayName("an ingredient no vendor supplies is counted and named, not costed at zero")
	void unpricedIngredientIsNamed() throws Exception {
		plan(khichdi, "200", "PLANNED");

		cost().andExpect(status().isOk())
				.andExpect(jsonPath("$.ingredientsWithoutPrice").value(1))
				.andExpect(jsonPath("$.unpriced.length()").value(1))
				.andExpect(jsonPath("$.unpriced[0].name").value("Rock Salt"))
				.andExpect(jsonPath("$.unpriced[0].quantity").value(0.4))
				.andExpect(jsonPath("$.unpriced[0].unit").value("KG"));
	}

	@Test
	@DisplayName("a cancelled meal is not part of the day's bill")
	void cancelledMealIsExcluded() throws Exception {
		plan(khichdi, "200", "PLANNED");
		plan(payasam, "100", "PLANNED");
		plan(payasam, "100", "CANCELLED");

		cost().andExpect(jsonPath("$.estimatedTotal").value(1200.00));
	}

	@Test
	@DisplayName("a meal that has been cooked still counts — the figure must not drain through the day")
	void cookedMealStillCounts() throws Exception {
		plan(khichdi, "200", "COOKED");
		plan(payasam, "100", "PLANNED");

		cost().andExpect(jsonPath("$.estimatedTotal").value(1200.00));
	}

	@Test
	@DisplayName("a day with nothing planned costs nothing, and says so without a gap")
	void emptyDay() throws Exception {
		cost().andExpect(jsonPath("$.estimatedTotal").value(0.00))
				.andExpect(jsonPath("$.ingredientsPriced").value(0))
				.andExpect(jsonPath("$.ingredientsWithoutPrice").value(0));
	}

	@Test
	@DisplayName("a volunteer cannot read what the day's food costs")
	void volunteerForbidden() throws Exception {
		signIn("uid-vol-a");
		cost().andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private org.springframework.test.web.servlet.ResultActions cost() throws Exception {
		return mvc.perform(get("/api/v1/materials-cost")
				.param("date", day.toString())
				.header("Authorization", "Bearer valid-token"));
	}

	private void plan(UUID recipeId, String servings, String status) {
		admin.update("""
				INSERT INTO meal_plans (tenant_id, plan_date, meal_kind, ready_by, recipe_id,
						target_servings, day_type, status, created_by)
				VALUES (?, ?, 'Lunch', TIME '12:00', ?, ?::numeric, 'REGULAR', ?,
						(SELECT id FROM users WHERE firebase_uid = 'uid-staff-a'))
				""", tenant, day, recipeId, servings, status);
	}

	private UUID recipe(String name, UUID category, int baseYield) {
		return admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, ?, ?, ?, 'SERVINGS') RETURNING id
				""", UUID.class, tenant, name, category, baseYield);
	}

	private void line(UUID recipeId, UUID ingredientId, String quantity, String unit, int order) {
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, ?::numeric, ?, ?)
				""", tenant, recipeId, ingredientId, quantity, unit, order);
	}

	private UUID ingredient(String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', 'KG') RETURNING id
				""", UUID.class, tenant, name);
	}

	private UUID vendor(String name) {
		return admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, ?, '+919812345678') RETURNING id
				""", UUID.class, tenant, name);
	}

	private void supply(UUID vendorId, UUID ingredientId, String lastPrice, boolean preferred) {
		admin.update("""
				INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price, preferred)
				VALUES (?, ?, ?, ?::numeric, ?)
				""", tenant, vendorId, ingredientId, lastPrice, preferred);
	}

	private void insertUser(String uid, String email, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				""", tenant, uid, email, role);
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
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
