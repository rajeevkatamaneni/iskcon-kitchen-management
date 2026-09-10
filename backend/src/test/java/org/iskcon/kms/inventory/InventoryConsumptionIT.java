package org.iskcon.kms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * CONSUMPTION movements, and a preview reports shortfalls without writing.
 *
 * <p><strong>A short commit is no longer refused (T-087).</strong> Preview is the planning question
 * and still answers no; commit says "this was cooked", which is a fact rather than a request, so it
 * draws what the batches hold and books the rest as {@code USED_BEYOND_RECORDED_STOCK}. The tests
 * below pin both halves against the same short shelf, because either one alone would pass against
 * an implementation that had simply deleted the check.
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

		assertThat(onHand(rice)).as("14 KG - 5 KG").isEqualByComparingTo("9000");
		assertThat(onHand(dal)).as("3 KG - 2 KG").isEqualByComparingTo("1000");
		assertThat(consumptionMovements()).isEqualTo(3); // rice from two batches + dal from one
	}

	/**
	 * <strong>The two questions, asked of the same short shelf, and answered differently (T-087).</strong>
	 *
	 * <p>Preview is the planning question and it still says no: {@code sufficient} comes back false
	 * and the dal is itemised with what was needed against what is there. Commit is the recording
	 * question — this was cooked — and it is not allowed to refuse a fact. It draws the dal batch to
	 * zero and books the missing kilo as its own {@code USED_BEYOND_RECORDED_STOCK} row.
	 *
	 * <p>Both halves are asserted in one test on purpose. Either alone would pass against a wrong
	 * implementation: a commit that never refuses passes if the check was simply deleted, and a
	 * preview that still refuses passes if nothing changed at all. What has to be true is that the
	 * same shelf gets two different answers, and only asking both proves it.
	 */
	@Test
	@DisplayName("planning still refuses a short shelf; recording books the shortfall instead")
	void planningRefusesAndRecordingBooksTheShortfall() throws Exception {
		// 200 servings needs 10 KG rice (have 14) and 4 KG dal (have 3) — dal is short by 1 KG.
		mvc.perform(consumeAt("/preview", "200", null))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sufficient").value(false))
				.andExpect(jsonPath("$.shortfalls[?(@.ingredientName=='Toor Dal')].required").value(4))
				.andExpect(jsonPath("$.shortfalls[?(@.ingredientName=='Toor Dal')].available").value(3));
		assertThat(consumptionMovements()).as("preview still writes nothing").isZero();

		// The same request, committed. It succeeds, and it still says what was short.
		mvc.perform(consumeAt("", "200", null))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.sufficient").value(false))
				.andExpect(jsonPath("$.shortfalls[?(@.ingredientName=='Toor Dal')].required").value(4));

		assertThat(onHand(rice)).as("rice was there: 14 KG - 10 KG").isEqualByComparingTo("4000");
		assertThat(onHand(dal))
				.as("the books held 3 KG and the kitchen used 4: the shelf is empty, not minus one (T-122)")
				.isEqualByComparingTo("0");
		assertThat(rawLedgerSum(dal))
				.as("and the missing kilo is still recorded — it just is not stock any more")
				.isEqualByComparingTo("-1000");
		assertThat(batchStock(dalBatch))
				.as("no lot the store room knows about goes negative — it was drawn to zero and stopped")
				.isEqualByComparingTo("0");

		Map<String, Object> booked = admin.queryForMap("""
				SELECT ingredient_id, batch_id, quantity, unit, note
				FROM stock_movements WHERE movement_type = 'USED_BEYOND_RECORDED_STOCK'
				""");
		assertThat(booked.get("ingredient_id")).as("the row names the ingredient to chase").isEqualTo(dal);
		assertThat((BigDecimal) booked.get("quantity")).isEqualByComparingTo("-1000");
		assertThat(booked.get("unit")).isEqualTo("GM");
		assertThat(booked.get("batch_id"))
				.as("its own lot id: this food came out of a delivery nobody wrote down")
				.isNotEqualTo(dalBatch);
		assertThat((String) booked.get("note"))
				.contains("Toor Dal")
				.contains("Check that every delivery of it has been recorded");

		// And it is findable where somebody would go looking: the ingredient's movement history,
		// filtered to the new kind. This is the list the whole ruling turns on.
		mvc.perform(get("/api/v1/inventory/movements")
						.header("Authorization", "Bearer valid-token")
						.param("ingredientId", dal.toString())
						.param("type", "USED_BEYOND_RECORDED_STOCK"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].type").value("USED_BEYOND_RECORDED_STOCK"))
				.andExpect(jsonPath("$[0].ingredientName").value("Toor Dal"));
	}

	/**
	 * The extreme of the same case: the temple cooked with something the store room has never held a
	 * gram of. There is no batch to draw to zero, so the whole requirement is booked, and the point
	 * is that the recording still goes through — a recipe naming an ingredient nobody ever received
	 * was the surest way to hit the old refusal.
	 */
	@Test
	@DisplayName("an ingredient the store has never held is booked in full, and the recording stands")
	void anIngredientWithNoBatchesAtAllIsBookedInFull() throws Exception {
		UUID ghee = insertIngredient(templeA, "Ghee", "KG");
		insertLine(recipeId, ghee, "1", 2);

		mvc.perform(consumeAt("", "100", null)).andExpect(status().isCreated());

		assertThat(onHand(ghee))
				.as("a store room that has never held a gram of ghee holds zero, never minus one kilo")
				.isEqualByComparingTo("0");
		assertThat(rawLedgerSum(ghee))
				.as("the kilo the kitchen used is still on the record")
				.isEqualByComparingTo("-1000");
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM stock_movements
				WHERE ingredient_id = ? AND movement_type = 'CONSUMPTION'
				""", Integer.class, ghee)).as("nothing to draw, so nothing was drawn").isZero();
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM stock_movements
				WHERE ingredient_id = ? AND movement_type = 'USED_BEYOND_RECORDED_STOCK'
				""", Integer.class, ghee)).isEqualTo(1);
	}

	/**
	 * <strong>What the storekeeper's two screens say afterwards (T-122).</strong>
	 *
	 * <p>This is the half of the correction that has a person on the end of it. Until now the stock
	 * list read <em>Toor Dal −1 Kg</em>, and the item's own screen listed the shortfall's private
	 * batch id among the real lots as a bare UUID holding minus one kilo, with no arrival date and
	 * no expiry — a lot that does not exist, on the list of lots that do. Rajeev saw both.
	 *
	 * <p>Neither is fixed by anything that knows about them. On hand is now summed through
	 * {@code to_on_hand_qty}, so the phantom lot aggregates to zero, and {@code get()} already drops
	 * a batch holding nothing.
	 */
	@Test
	@DisplayName("the stock screens read zero, and the shortfall's phantom lot is not among the real ones")
	void theInventoryScreensReadZeroAndListNoPhantomLot() throws Exception {
		mvc.perform(consumeAt("", "200", null)).andExpect(status().isCreated());

		UUID phantom = admin.queryForObject("""
				SELECT batch_id FROM stock_movements WHERE movement_type = 'USED_BEYOND_RECORDED_STOCK'
				""", UUID.class);
		UUID itemId = admin.queryForObject(
				"SELECT id FROM inventory_items WHERE ingredient_id = ?", UUID.class, dal);

		// The list screen. Nothing on it is allowed to say the temple holds less than nothing.
		mvc.perform(get("/api/v1/inventory/items").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.ingredientName=='Toor Dal')].onHand").value(0));

		// The item screen: the same total, and a lot list with only the lot that really existed —
		// which the store room drew to zero, so it is not there either.
		mvc.perform(get("/api/v1/inventory/items/{id}", itemId)
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.item.onHand").value(0))
				.andExpect(jsonPath("$.batches[?(@.batchId=='" + phantom + "')]").isEmpty())
				// The real lot is gone from the list too, and for the older, right reason: FEFO drew
				// it to zero and a lot holding nothing has never been listed. An ingredient that came
				// up short has no lot left with anything in it, so the list being empty is the only
				// honest answer — what changed is that the row above it is no longer a UUID nobody
				// can look up, holding minus one kilo, dated nowhere.
				.andExpect(jsonPath("$.batches[?(@.batchId=='" + dalBatch + "')]").isEmpty())
				.andExpect(jsonPath("$.batches.length()").value(0));

		// And the row itself is untouched: still there, still a kilo, still naming the ingredient.
		mvc.perform(get("/api/v1/inventory/movements")
						.header("Authorization", "Bearer valid-token")
						.param("ingredientId", dal.toString())
						.param("type", "USED_BEYOND_RECORDED_STOCK"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].quantity").value(-1000.0))
				.andExpect(jsonPath("$[0].ingredientName").value("Toor Dal"));
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
				.andExpect(jsonPath("$.code").value("KMS-400001"));
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

	/**
	 * What the shelf holds, computed the way the application computes it (V116, T-122) — the
	 * discrepancy rows count as zero, so this is the figure the six readers report.
	 */
	private BigDecimal onHand(UUID ingredientId) {
		return admin.queryForObject("""
				SELECT COALESCE(SUM(to_on_hand_qty(quantity, unit, movement_type)), 0)
				FROM stock_movements WHERE ingredient_id = ?
				""", BigDecimal.class, ingredientId);
	}

	/**
	 * Every quantity in the ledger added up regardless of what kind of row it is — which is what on
	 * hand used to be, and is now no figure the application shows anybody. Kept because the tests
	 * need to say <em>the record is still there and still says minus one kilo</em> as distinctly
	 * from <em>and it did not come off the shelf</em>.
	 */
	private BigDecimal rawLedgerSum(UUID ingredientId) {
		return admin.queryForObject("""
				SELECT COALESCE(SUM(to_base_qty(quantity, unit)), 0)
				FROM stock_movements WHERE ingredient_id = ?
				""", BigDecimal.class, ingredientId);
	}

	private BigDecimal batchStock(UUID batchId) {
		return admin.queryForObject("""
				SELECT COALESCE(SUM(to_on_hand_qty(quantity, unit, movement_type)), 0)
				FROM stock_movements WHERE batch_id = ?
				""", BigDecimal.class, batchId);
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
				VALUES (?, ?, ?, ?, 'KG') RETURNING id
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
