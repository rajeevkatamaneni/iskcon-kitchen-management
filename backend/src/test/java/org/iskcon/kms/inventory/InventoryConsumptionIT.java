package org.iskcon.kms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
 * Cooking a meal draws stock down (E3-S6): a scaled recipe becomes FEFO batch draws and negative
 * CONSUMPTION movements, a preview reports shortfalls without writing, and a short commit is refused
 * in full — no partial writes.
 */
@AutoConfigureMockMvc
@Import(InventoryConsumptionIT.StubVerifierConfiguration.class)
class InventoryConsumptionIT extends AbstractIntegrationTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID actorA;
	private UUID rice;
	private UUID dal;
	private UUID recipeId;
	private UUID riceLater;   // 10 KG, expires in 60 days
	private UUID riceSoon;    // 4 KG, expires in 3 days
	private UUID dalBatch;    // 3 KG

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		actorA = insertUser(templeA, "uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-vol-a", "vol-a@example.com", "VOLUNTEER");
		rice = insertIngredient(templeA, "Rice", "KG");
		dal = insertIngredient(templeA, "Toor Dal", "KG");

		// Khichdi: 100 servings from 5 KG rice + 2 KG dal.
		recipeId = insertRecipe(templeA, "Khichdi", new BigDecimal("100"));
		insertLine(recipeId, rice, "5", 0);
		insertLine(recipeId, dal, "2", 1);

		LocalDate today = LocalDate.now(IST);
		riceLater = UUID.randomUUID();
		riceSoon = UUID.randomUUID();
		dalBatch = UUID.randomUUID();
		seedReceipt(rice, riceLater, "10", today.plusDays(60));
		seedReceipt(rice, riceSoon, "4", today.plusDays(3));
		seedReceipt(dal, dalBatch, "3", today.plusDays(90));

		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("preview draws FEFO and reports sufficiency without writing anything")
	void previewIsFefoAndWritesNothing() throws Exception {
		mvc.perform(consumeAt("/preview", "100", null))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sufficient").value(true))
				.andExpect(jsonPath("$.shortfalls.length()").value(0))
				// Rice: 5 KG needed, drawn first from the batch expiring soonest (4) then the later (1).
				.andExpect(jsonPath("$.lines[?(@.ingredientName=='Rice')].draws[0].batchId")
						.value(riceSoon.toString()))
				.andExpect(jsonPath("$.lines[?(@.ingredientName=='Rice')].draws[0].quantity").value(4))
				.andExpect(jsonPath("$.lines[?(@.ingredientName=='Rice')].draws[1].quantity").value(1));

		assertThat(consumptionMovements()).as("preview writes nothing").isZero();
	}

	@Test
	@DisplayName("consume writes the negative movements and reduces stock")
	void consumeReducesStock() throws Exception {
		mvc.perform(consumeAt("", "100", null)).andExpect(status().isCreated());

		assertThat(baseStock(rice)).as("14 KG - 5 KG").isEqualByComparingTo("9000");
		assertThat(baseStock(dal)).as("3 KG - 2 KG").isEqualByComparingTo("1000");
		assertThat(consumptionMovements()).isEqualTo(3); // rice from two batches + dal from one
	}

	@Test
	@DisplayName("a shortfall is itemised on preview and refuses the whole commit")
	void shortfallRefusesInFull() throws Exception {
		// 200 servings needs 10 KG rice (have 14) and 4 KG dal (have 3) — dal is short.
		mvc.perform(consumeAt("/preview", "200", null))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sufficient").value(false))
				.andExpect(jsonPath("$.shortfalls[?(@.ingredientName=='Toor Dal')].required").value(4))
				.andExpect(jsonPath("$.shortfalls[?(@.ingredientName=='Toor Dal')].available").value(3));

		mvc.perform(consumeAt("", "200", null))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4911"));

		assertThat(baseStock(rice)).as("no partial writes: rice untouched by the failed commit")
				.isEqualByComparingTo("14000");
		assertThat(consumptionMovements()).isZero();
	}

	@Test
	@DisplayName("a batch override pulls the chosen batch to the front of the draw")
	void batchOverrideFrontLoads() throws Exception {
		String overrides = "[{\"ingredientId\":\"" + rice + "\",\"batchId\":\"" + riceLater + "\"}]";
		mvc.perform(consumeAt("/preview", "100", overrides))
				.andExpect(status().isOk())
				// Rice now drawn from the later batch first (it holds 10, enough for all 5).
				.andExpect(jsonPath("$.lines[?(@.ingredientName=='Rice')].draws[0].batchId")
						.value(riceLater.toString()))
				.andExpect(jsonPath("$.lines[?(@.ingredientName=='Rice')].draws.length()").value(1));
	}

	@Test
	@DisplayName("overriding to a batch that holds nothing is rejected")
	void unknownOverrideBatchRejected() throws Exception {
		String overrides = "[{\"ingredientId\":\"" + rice + "\",\"batchId\":\"" + UUID.randomUUID() + "\"}]";
		mvc.perform(consumeAt("/preview", "100", overrides))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4001"));
	}

	@Test
	@DisplayName("a volunteer cannot consume stock")
	void volunteerForbidden() throws Exception {
		signIn("uid-vol-a");
		mvc.perform(consumeAt("/preview", "100", null)).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private MockHttpServletRequestBuilder consumeAt(String suffix, String yield, String overridesJson) {
		StringBuilder json = new StringBuilder("{\"recipeId\":\"").append(recipeId)
				.append("\",\"targetYield\":").append(yield);
		if (overridesJson != null) {
			json.append(",\"batchOverrides\":").append(overridesJson);
		}
		json.append("}");
		return post("/api/v1/inventory/consumption" + suffix)
				.header("Authorization", "Bearer valid-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.toString());
	}

	private BigDecimal baseStock(UUID ingredientId) {
		return admin.queryForObject("""
				SELECT COALESCE(SUM(quantity * CASE unit WHEN 'KG' THEN 1000 WHEN 'L' THEN 1000 ELSE 1 END), 0)
				FROM stock_movements WHERE ingredient_id = ?
				""", BigDecimal.class, ingredientId);
	}

	private int consumptionMovements() {
		Integer c = admin.queryForObject(
				"SELECT count(*) FROM stock_movements WHERE movement_type = 'CONSUMPTION'", Integer.class);
		return c == null ? 0 : c;
	}

	private void seedReceipt(UUID ingredient, UUID batch, String qtyKg, LocalDate expiry) {
		admin.update("""
				INSERT INTO stock_movements (
					tenant_id, ingredient_id, batch_id, quantity, unit, movement_type,
					expiry_date, received_date, actor_user_id)
				VALUES (?, ?, ?, ?::numeric, 'KG', 'PO_RECEIPT', ?, ?, ?)
				""", templeA, ingredient, batch, qtyKg, expiry, expiry, actorA);
	}

	private UUID insertRecipe(UUID tenant, String name, BigDecimal baseYield) {
		UUID categoryId = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, ?) RETURNING id
				""", UUID.class, tenant, "Rice");
		return admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, ?, ?, ?, 'SERVINGS') RETURNING id
				""", UUID.class, tenant, name, categoryId, baseYield);
	}

	private void insertLine(UUID recipe, UUID ingredient, String qtyKg, int order) {
		admin.update("""
				INSERT INTO recipe_ingredients (
					tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, ?::numeric, 'KG', ?)
				""", templeA, recipe, ingredient, qtyKg, order);
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

	private UUID insertUser(UUID tenantId, String uid, String email, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				RETURNING id
				""", UUID.class, tenantId, uid, email, role);
	}

	private UUID insertIngredient(UUID tenantId, String name, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', ?)
				RETURNING id
				""", UUID.class, tenantId, name, unit);
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
