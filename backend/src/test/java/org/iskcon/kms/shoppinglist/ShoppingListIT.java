package org.iskcon.kms.shoppinglist;

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
 * The auto-generated shopping list (E5-S2): merged shortfall + threshold streams with provenance,
 * preferred-vendor suggestion, and an edit-preserving regeneration.
 */
@AutoConfigureMockMvc
@Import(ShoppingListIT.StubVerifierConfiguration.class)
class ShoppingListIT extends AbstractIntegrationTest {

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

		rice = ingredient("Rice");
		UUID garlic = ingredient("Garlic");
		item(rice, "10");    // reorder threshold 10 KG
		item(garlic, "5");   // reorder threshold 5 KG
		receipt(rice, "3");  // 3 KG on hand -> below threshold; topUp = 12 - 3 = 9
		receipt(garlic, "1"); // below threshold; topUp = 6 - 1 = 5

		// A planned meal drives a rice shortfall: 200-serving Khichdi needs 10 KG, only 3 in stock.
		UUID cat = admin.queryForObject("INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Rice') RETURNING id", UUID.class, tenant);
		UUID khichdi = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, 'Khichdi', ?, 100, 'KG') RETURNING id
				""", UUID.class, tenant, cat);
		admin.update("INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order) VALUES (?, ?, ?, 5, 'KG', 0)", tenant, khichdi, rice);
		LocalDate soon = LocalDate.now(IST).plusDays(2);
		admin.update("""
				INSERT INTO meal_plans (tenant_id, plan_date, meal_kind, ready_by, recipe_id, target_yield, day_type, status, created_by)
				VALUES (?, ?, 'Lunch', TIME '12:00', ?, 200, 'REGULAR', 'PLANNED', ?)
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
		admin.execute("DELETE FROM shopping_list_lines");
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
		mvc.perform(regenerate()).andExpect(status().isOk()).andExpect(jsonPath("$.lines").value(2));

		mvc.perform(authed(get("/api/v1/shopping-list")))
				// Two lines, ordered by ingredient name: Garlic then Rice. Garlic is here at all only
				// because D-18 removed the exclusion that used to keep it out — see
				// garlicNoLongerExcluded below, which is where that is asserted deliberately.
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].ingredientName").value("Garlic"))
				.andExpect(jsonPath("$[1].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[1].suggestedQty").value(9))       // max(shortfall 7, topUp 9)
				.andExpect(jsonPath("$[1].shortfall").value(7))          // E4-S5 contract
				.andExpect(jsonPath("$[1].thresholdTopUp").value(9))
				.andExpect(jsonPath("$[1].suggestedVendorName").value("Govind Wholesale"))
				.andExpect(jsonPath("$[1].neededBy").exists());
	}

	/**
	 * The order-by date on a line (T-090): the earliest meal that wants the ingredient, minus the
	 * lead time recorded against the vendor the order would go to.
	 *
	 * <p>Three readings off one row, in the order a temple actually arrives at them.
	 *
	 * <ol>
	 *   <li><strong>Nothing recorded.</strong> The two-day assumption stands in, and the line says so
	 *       — {@code leadTimeDays} comes back absent rather than as a 2, so the screen can print
	 *       "assumed" and somebody can go and record the real answer. This is the case that must not
	 *       be confused with a lead time of zero: the meal is two days out, so a zero would say the
	 *       order can wait until the morning it is cooked.</li>
	 *   <li><strong>Five days recorded.</strong> The date moves three days into the past and the line
	 *       reads "too late" — the state whose sentence has to be different, because there is no
	 *       longer an order that arrives in time.</li>
	 *   <li><strong>Zero recorded.</strong> Cash-and-carry: the date is the meal's own day, and the
	 *       line has slack again. Note it is a different answer from case 1, which is the whole point
	 *       of the column being nullable.</li>
	 * </ol>
	 *
	 * <p>Garlic is on this list through the threshold stream alone — no meal demanded it — so it has
	 * no demand date to count back from and gets no order-by date. An em dash on the screen, and
	 * deliberately not today: nobody is late for a top-up nothing has asked for by a date.
	 */
	@Test
	@DisplayName("the order-by date counts back from the meal by the vendor's lead time, and says when it was assumed")
	void orderByCountsBackFromTheDemand() throws Exception {
		LocalDate today = LocalDate.now(IST);

		mvc.perform(regenerate()).andExpect(status().isOk());
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$[1].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[1].orderBy").value(today.toString()))
				.andExpect(jsonPath("$[1].orderUrgency").value("ORDER_TODAY"))
				.andExpect(jsonPath("$[1].leadTimeDays").doesNotExist())
				// The delivery date on the purchase order is a different question and is untouched.
				.andExpect(jsonPath("$[1].neededBy").value(today.toString()))
				// Nothing demanded the garlic by a date, so there is no deadline to invent.
				.andExpect(jsonPath("$[0].ingredientName").value("Garlic"))
				.andExpect(jsonPath("$[0].orderBy").doesNotExist())
				.andExpect(jsonPath("$[0].orderUrgency").doesNotExist());

		admin.update("UPDATE vendor_supplies SET lead_time_days = 5 WHERE ingredient_id = ?", rice);
		mvc.perform(regenerate()).andExpect(status().isOk());
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$[1].orderBy").value(today.minusDays(3).toString()))
				.andExpect(jsonPath("$[1].orderUrgency").value("TOO_LATE"))
				.andExpect(jsonPath("$[1].leadTimeDays").value(5));

		admin.update("UPDATE vendor_supplies SET lead_time_days = 0 WHERE ingredient_id = ?", rice);
		mvc.perform(regenerate()).andExpect(status().isOk());
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$[1].orderBy").value(today.plusDays(2).toString()))
				.andExpect(jsonPath("$[1].orderUrgency").value("IN_TIME"))
				.andExpect(jsonPath("$[1].leadTimeDays").value(0));
	}

	@Test
	@DisplayName("what used to be a sattvic-prohibited ingredient now enters via the threshold stream")
	void garlicNoLongerExcluded() throws Exception {
		// The inverse of the test that stood here until 2026-09-08, which asserted that garlic could
		// NEVER reach the list this way. D-18 deleted the flag the exclusion read, and this is the
		// accepted consequence stated in as many words: a temple that keeps an inventory row for
		// garlic, with a reorder threshold on it, is now topped up like any other stock. Asserted
		// deliberately rather than left for somebody to find later and report as a defect.
		mvc.perform(regenerate());
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$[?(@.ingredientName=='Garlic')]").exists())
				.andExpect(jsonPath("$[0].ingredientName").value("Garlic"))
				.andExpect(jsonPath("$[0].thresholdTopUp").value(5))   // 5 × 1.2 = 6, less 1 on hand
				.andExpect(jsonPath("$[0].shortfall").value(0));       // no meal plan asks for it
	}

	@Test
	@DisplayName("regeneration preserves human edits to quantity and inclusion")
	void editsSurviveRegeneration() throws Exception {
		mvc.perform(regenerate());
		mvc.perform(authed(patch("/api/v1/shopping-list/{id}", rice))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"suggestedQty\":20,\"included\":false}"))
				.andExpect(status().isNoContent());

		mvc.perform(regenerate()); // re-run

		// Index 1: the list is ordered by name, and Garlic now sits ahead of Rice.
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$[1].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[1].suggestedQty").value(20))
				.andExpect(jsonPath("$[1].included").value(false))
				.andExpect(jsonPath("$[1].edited").value(true));
	}

	@Test
	@DisplayName("a partial edit that omits the vendor leaves the suggested vendor alone")
	void editWithoutVendorKeepsTheSuggestedVendor() throws Exception {
		mvc.perform(regenerate());
		// The regeneration suggested the preferred vendor; this is the value the edit must not destroy.
		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$[1].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[1].suggestedVendorName").value("Govind Wholesale"));

		// Exactly what both callers on the shopping-list screen send — the include toggle and the
		// quantity edit each PATCH quantity and inclusion only, never the vendor. A PATCH that omits a
		// field must leave it as it was; writing the absent id straight through nulls the vendor and
		// silently drops the line out of ordering, because generation only picks up lines that have one.
		mvc.perform(authed(patch("/api/v1/shopping-list/{id}", rice))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"suggestedQty\":15,\"included\":true}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(jsonPath("$[1].ingredientName").value("Rice"))
				.andExpect(jsonPath("$[1].suggestedQty").value(15))
				.andExpect(jsonPath("$[1].suggestedVendorId").isNotEmpty())
				.andExpect(jsonPath("$[1].suggestedVendorName").value("Govind Wholesale"));
	}

	@Test
	@DisplayName("a volunteer cannot see the shopping list")
	void volunteerForbidden() throws Exception {
		signIn("uid-vol-a");
		mvc.perform(authed(get("/api/v1/shopping-list"))).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private MockHttpServletRequestBuilder regenerate() {
		return authed(post("/api/v1/shopping-list/regenerate"));
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private UUID ingredient(String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', 'KG') RETURNING id
				""", UUID.class, tenant, name);
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
