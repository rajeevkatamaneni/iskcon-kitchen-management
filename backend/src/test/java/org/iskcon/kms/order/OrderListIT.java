package org.iskcon.kms.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * The auto-generated order list (E5-S2): merged shortfall + threshold streams with provenance, the
 * sattvic guard, preferred-vendor suggestion, and an edit-preserving regeneration.
 */
@AutoConfigureMockMvc
@Import(OrderListIT.StubVerifierConfiguration.class)
class OrderListIT extends AbstractIntegrationTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID rice;
	private UUID staffId;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		staffId = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-staff-a', 'Staff A', 'staff-a@example.com', '+919876500081', 'KITCHEN_STAFF', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-vol-a', 'Vol A', 'vol-a@example.com', '+919876500082', 'VOLUNTEER', 'ACTIVE')
				""", tenant);

		rice = ingredient("Rice", false);
		UUID garlic = ingredient("Garlic", true);
		item(rice, "10");    // reorder threshold 10 KG
		item(garlic, "5");   // reorder threshold 5 KG
		receipt(rice, "3");  // 3 KG on hand -> below threshold; topUp = 12 - 3 = 9
		receipt(garlic, "1"); // below threshold but sattvic -> excluded

		// A planned meal drives a rice shortfall: 200-serving Khichdi needs 10 KG, only 3 in stock.
		UUID cat = admin.queryForObject("INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Rice') RETURNING id", UUID.class, tenant);
		UUID khichdi = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, 'Khichdi', ?, 100, 'SERVINGS') RETURNING id
				""", UUID.class, tenant, cat);
		admin.update("INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order) VALUES (?, ?, ?, 5, 'KG', 0)", tenant, khichdi, rice);
		LocalDate soon = LocalDate.now(IST).plusDays(2);
		admin.update("""
				INSERT INTO meal_plans (tenant_id, plan_date, slot, recipe_id, target_servings, day_type, status, created_by)
				VALUES (?, ?, 'Lunch', ?, 200, 'REGULAR', 'PLANNED', ?)
				""", tenant, soon, khichdi, staffId);

		// Preferred vendor for rice.
		UUID vendor = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Govind Wholesale', '+919812345678') RETURNING id
				""", UUID.class, tenant);
		admin.update("""
				INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, preferred) VALUES (?, ?, ?, true)
				""", tenant, vendor, rice);

		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM order_list_lines");
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM meal_plans");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("regeneration merges shortfall + threshold with provenance, suggests the preferred vendor")
	void mergesStreamsWithProvenance() throws Exception {
		mvc.perform(regenerate()).andExpect(status().isOk()).andExpect(jsonPath("$.lines").value(1));

		mvc.perform(authed(get("/api/v1/order-list")))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[0].suggestedQty").value(9))       // max(shortfall 7, topUp 9)
				.andExpect(jsonPath("$[0].shortfall").value(7))          // E4-S5 contract
				.andExpect(jsonPath("$[0].thresholdTopUp").value(9))
				.andExpect(jsonPath("$[0].suggestedVendorName").value("Govind Wholesale"))
				.andExpect(jsonPath("$[0].neededBy").exists());
	}

	@Test
	@DisplayName("a sattvic-prohibited ingredient never enters via the threshold stream")
	void garlicExcluded() throws Exception {
		mvc.perform(regenerate());
		mvc.perform(authed(get("/api/v1/order-list")))
				.andExpect(jsonPath("$[?(@.ingredientName=='Garlic')]").doesNotExist());
	}

	@Test
	@DisplayName("regeneration preserves human edits to quantity and inclusion")
	void editsSurviveRegeneration() throws Exception {
		mvc.perform(regenerate());
		mvc.perform(authed(patch("/api/v1/order-list/{id}", rice))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"suggestedQty\":20,\"included\":false}"))
				.andExpect(status().isNoContent());

		mvc.perform(regenerate()); // re-run

		mvc.perform(authed(get("/api/v1/order-list")))
				.andExpect(jsonPath("$[0].suggestedQty").value(20))
				.andExpect(jsonPath("$[0].included").value(false))
				.andExpect(jsonPath("$[0].edited").value(true));
	}

	@Test
	@DisplayName("a volunteer cannot see the order list")
	void volunteerForbidden() throws Exception {
		signIn("uid-vol-a");
		mvc.perform(authed(get("/api/v1/order-list"))).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private MockHttpServletRequestBuilder regenerate() {
		return authed(post("/api/v1/order-list/regenerate"));
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private UUID ingredient(String name, boolean sattvic) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit, is_sattvic_prohibited)
				VALUES (?, ?, 'Grains', 'KG', ?) RETURNING id
				""", UUID.class, tenant, name, sattvic);
	}

	private void item(UUID ingredient, String threshold) {
		admin.update("""
				INSERT INTO inventory_items (tenant_id, ingredient_id, reorder_threshold)
				VALUES (?, ?, ?::numeric)
				""", tenant, ingredient, threshold);
	}

	private void receipt(UUID ingredient, String qtyKg) {
		admin.update("""
				INSERT INTO stock_movements (tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
				VALUES (?, ?, ?, ?::numeric, 'KG', 'PO_RECEIPT', ?)
				""", tenant, ingredient, UUID.randomUUID(), qtyKg, staffId);
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
