package org.iskcon.kms.inventory;

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
 * Consumable inventory and the derived stock view (E3-S1) through the full stack: stock is the sum of
 * movements (across mixed units of one family), batches are FEFO with an expiring-soon badge, the
 * below-threshold badge is computed, and the 1:1-per-ingredient rule and RLS both hold.
 *
 * <p>Since T-086 it also covers the three figures the screen shows: on hand, committed, and
 * available — which is derived on every read and stored nowhere. The tests worth reading twice are
 * the exclusions, because getting one of them wrong makes every number on the screen wrong: a plan
 * that has been recorded must not be committed as well (its stock has already left the ledger, and
 * counting both subtracts it twice), and nor must a cancelled one, a past one nobody recorded, or
 * one beyond the horizon the temple is buying against.
 *
 * <p>Kept in this class rather than in one of its own on purpose. {@code @Import} is part of the
 * Spring test context cache key, so a new integration test class with its own stub configuration is
 * a whole extra application context — which is how CI has run out of heap before with nothing
 * failing.
 */
@AutoConfigureMockMvc
@Import(InventoryStockIT.StubVerifierConfiguration.class)
class InventoryStockIT extends AbstractIntegrationTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID templeB;
	private UUID actorA;
	private UUID toorDal;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
		actorA = insertUser(templeA, "uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-vol-a", "vol-a@example.com", "VOLUNTEER");
		toorDal = insertIngredient(templeA, "Toor Dal", "KG");
		signIn("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM meal_plans");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a new item shows zero on hand and, with a threshold set, reads as below it")
	void newItemIsBelowThreshold() throws Exception {
		createItem(toorDal, "Main store", "5");

		mvc.perform(authed(get("/api/v1/inventory/items")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].ingredientName").value("Toor Dal"))
				.andExpect(jsonPath("$[0].onHand").value(0))
				.andExpect(jsonPath("$[0].belowThreshold").value(true))
				.andExpect(jsonPath("$[0].expiringSoon").value(false));

		assertAudit("INVENTORY_ITEM_ADDED", 1);
	}

	@Test
	@DisplayName("on hand is the sum of movements, across mixed units of the same family")
	void onHandIsSumAcrossUnits() throws Exception {
		createItem(toorDal, "Main store", "5");
		UUID batch = UUID.randomUUID();
		// 2 KG + 500 GM received, 250 GM consumed => 2250 gm = 2.25 KG (still below the 5 KG threshold).
		seedMovement(templeA, toorDal, batch, "2", "KG", MovementType.PO_RECEIPT, null);
		seedMovement(templeA, toorDal, batch, "500", "GM", MovementType.PO_RECEIPT, null);
		seedMovement(templeA, toorDal, batch, "-250", "GM", MovementType.CONSUMPTION, null);

		mvc.perform(authed(get("/api/v1/inventory/items")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].onHand").value(2.25))
				.andExpect(jsonPath("$[0].unit").value("KG"))
				.andExpect(jsonPath("$[0].belowThreshold").value(true));
	}

	@Test
	@DisplayName("detail lists batches first-expiry-first and flags the one expiring soon")
	void detailIsFefoWithExpiringSoonBadge() throws Exception {
		UUID itemId = createItem(toorDal, "Main store", null);
		LocalDate today = LocalDate.now(IST);

		UUID soonBatch = UUID.randomUUID();
		UUID laterBatch = UUID.randomUUID();
		seedMovement(templeA, toorDal, laterBatch, "10", "KG", MovementType.PO_RECEIPT, today.plusDays(60));
		seedMovement(templeA, toorDal, soonBatch, "4", "KG", MovementType.PO_RECEIPT, today.plusDays(3));

		mvc.perform(authed(get("/api/v1/inventory/items/{id}", itemId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.item.onHand").value(14))
				.andExpect(jsonPath("$.item.expiringSoon").value(true))
				.andExpect(jsonPath("$.batches.length()").value(2))
				// FEFO: the batch expiring in 3 days comes first and is badged.
				.andExpect(jsonPath("$.batches[0].batchId").value(soonBatch.toString()))
				.andExpect(jsonPath("$.batches[0].expiringSoon").value(true))
				.andExpect(jsonPath("$.batches[1].batchId").value(laterBatch.toString()))
				.andExpect(jsonPath("$.batches[1].expiringSoon").value(false));
	}

	@Test
	@DisplayName("a fully consumed batch drops out of the on-shelf batch list")
	void consumedBatchDropsOut() throws Exception {
		UUID itemId = createItem(toorDal, "Main store", null);
		UUID batch = UUID.randomUUID();
		seedMovement(templeA, toorDal, batch, "5", "KG", MovementType.PO_RECEIPT, null);
		seedMovement(templeA, toorDal, batch, "-5", "KG", MovementType.CONSUMPTION, null);

		mvc.perform(authed(get("/api/v1/inventory/items/{id}", itemId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.item.onHand").value(0))
				.andExpect(jsonPath("$.batches.length()").value(0));
	}

	@Test
	@DisplayName("an ingredient can be tracked only once")
	void oneItemPerIngredient() throws Exception {
		createItem(toorDal, "Main store", null);

		mvc.perform(createRequest(toorDal, "Cold room", null))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400040"));
	}

	@Test
	@DisplayName("an item can be filtered by storage location")
	void filtersByLocation() throws Exception {
		UUID rice = insertIngredient(templeA, "Rice", "KG");
		createItem(toorDal, "Main store", null);
		createItem(rice, "Cold room", null);

		mvc.perform(authed(get("/api/v1/inventory/items")).param("location", "Cold room"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].ingredientName").value("Rice"));
	}

	@Test
	@DisplayName("you cannot track another temple's ingredient")
	void cannotTrackForeignIngredient() throws Exception {
		UUID foreign = insertIngredient(templeB, "Payasam Base", "L");

		mvc.perform(createRequest(foreign, "Main store", null))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-400030"));
	}

	@Test
	@DisplayName("a volunteer cannot see the stock view")
	void volunteerCannotView() throws Exception {
		signIn("uid-vol-a");
		mvc.perform(authed(get("/api/v1/inventory/items"))).andExpect(status().isForbidden());
	}

	// ---- On hand, committed, available (T-086) ---------------------------

	@Test
	@DisplayName("available is on hand minus committed, and is derived rather than stored")
	void availableIsOnHandMinusCommitted() throws Exception {
		UUID itemId = createItem(toorDal, "Main store", null);
		seedMovement(templeA, toorDal, UUID.randomUUID(), "50", "KG", MovementType.PO_RECEIPT, null);
		UUID recipe = insertKhichadi();
		// 5 KG of dal per 100 of yield, so 600 of yield claims 30 KG.
		UUID plan = planMeal(recipe, LocalDate.now(IST).plusDays(2), "Lunch", "600", "PLANNED");

		mvc.perform(authed(get("/api/v1/inventory/items")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].onHand").value(50))
				.andExpect(jsonPath("$[0].committed").value(30))
				.andExpect(jsonPath("$[0].available").value(20));

		// Derived, not stored, and this is how you tell: calling the plan off moves `available` back
		// to 50 without a single movement being written. A stored figure could not do that, and a
		// stored figure is exactly what would drift away from the ledger.
		admin.update("UPDATE meal_plans SET status = 'CANCELLED' WHERE id = ?", plan);
		int movements = admin.queryForObject(
				"SELECT count(*) FROM stock_movements WHERE ingredient_id = ?", Integer.class, toorDal);
		org.assertj.core.api.Assertions.assertThat(movements).isEqualTo(1);

		mvc.perform(authed(get("/api/v1/inventory/items/{id}", itemId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.item.onHand").value(50))
				.andExpect(jsonPath("$.item.committed").value(0))
				.andExpect(jsonPath("$.item.available").value(50));
	}

	@Test
	@DisplayName("a recorded day's plan is not committed again — the stock has already moved")
	void recordedPlanIsNotSubtractedTwice() throws Exception {
		createItem(toorDal, "Main store", null);
		seedMovement(templeA, toorDal, UUID.randomUUID(), "50", "KG", MovementType.PO_RECEIPT, null);
		UUID recipe = insertKhichadi();

		// Recorded: the plan reads COOKED and recording drew 30 KG out of the ledger. If the plan
		// were still counted as committed the same 30 KG would come off twice and the screen would
		// show 20 KG less than the temple has — the double subtraction this filter exists to stop.
		planMeal(recipe, LocalDate.now(IST), "Lunch", "600", "COOKED");
		seedMovement(templeA, toorDal, UUID.randomUUID(), "-30", "KG", MovementType.CONSUMPTION, null);

		mvc.perform(authed(get("/api/v1/inventory/items")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].onHand").value(20))
				.andExpect(jsonPath("$[0].committed").value(0))
				.andExpect(jsonPath("$[0].available").value(20));
	}

	@Test
	@DisplayName("a cancelled plan, a past plan nobody recorded, and one beyond the buying horizon do not commit stock")
	void onlyLivePlansInsideTheHorizonCommit() throws Exception {
		createItem(toorDal, "Main store", null);
		seedMovement(templeA, toorDal, UUID.randomUUID(), "50", "KG", MovementType.PO_RECEIPT, null);
		UUID recipe = insertKhichadi();

		// Called off before cooking: it will never draw anything.
		planMeal(recipe, LocalDate.now(IST).plusDays(1), "Lunch", "600", "CANCELLED");
		// Still PLANNED with yesterday's date. Its stock has not moved, so counting it would not
		// double-subtract — but it is a recording gap rather than a claim on the shelf, and one that
		// never resolves itself. Left in, every unrecorded day would shave availability for ever.
		planMeal(recipe, LocalDate.now(IST).minusDays(1), "Lunch", "600", "PLANNED");
		// Three months out. It will be cooked from dal nobody has bought yet, so subtracting it from
		// today's sack would say "you are short" when the true answer is "you will buy it".
		planMeal(recipe, LocalDate.now(IST).plusDays(90), "Lunch", "600", "PLANNED");

		mvc.perform(authed(get("/api/v1/inventory/items")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].onHand").value(50))
				.andExpect(jsonPath("$[0].committed").value(0))
				.andExpect(jsonPath("$[0].available").value(50));
	}

	@Test
	@DisplayName("Low judges available: plenty on hand, nearly all of it committed, reads Low")
	void lowJudgesAvailableRatherThanOnHand() throws Exception {
		createItem(toorDal, "Main store", "25");
		seedMovement(templeA, toorDal, UUID.randomUUID(), "415.41", "KG", MovementType.PO_RECEIPT, null);
		UUID recipe = insertKhichadi();

		// 415.41 KG on hand against a 25 KG reorder level is comfortably fine on the old reading.
		mvc.perform(authed(get("/api/v1/inventory/items")))
				.andExpect(jsonPath("$[0].belowThreshold").value(false));

		// 8200 of yield claims 410 KG, leaving 5.41. Nothing physical changed; the answer must.
		planMeal(recipe, LocalDate.now(IST).plusDays(3), "Lunch", "8200", "PLANNED");

		mvc.perform(authed(get("/api/v1/inventory/items")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].onHand").value(415.41))
				.andExpect(jsonPath("$[0].committed").value(410))
				.andExpect(jsonPath("$[0].available").value(5.41))
				.andExpect(jsonPath("$[0].belowThreshold").value(true));
	}

	@Test
	@DisplayName("over-promising reads Low even where no reorder level has ever been set")
	void overCommittedWithoutAThresholdIsStillLow() throws Exception {
		createItem(toorDal, "Main store", null);
		seedMovement(templeA, toorDal, UUID.randomUUID(), "10", "KG", MovementType.PO_RECEIPT, null);
		UUID recipe = insertKhichadi();
		planMeal(recipe, LocalDate.now(IST).plusDays(2), "Lunch", "400", "PLANNED");

		mvc.perform(authed(get("/api/v1/inventory/items")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].available").value(-10))
				.andExpect(jsonPath("$[0].belowThreshold").value(true));
	}

	@Test
	@DisplayName("the detail page names the meals that claimed the stock, and the day each belongs to")
	void detailListsTheMealsThatCommittedTheStock() throws Exception {
		UUID itemId = createItem(toorDal, "Main store", "25");
		seedMovement(templeA, toorDal, UUID.randomUUID(), "50", "KG", MovementType.PO_RECEIPT, null);
		UUID recipe = insertKhichadi();
		LocalDate lunchDay = LocalDate.now(IST).plusDays(2);
		LocalDate eventDay = LocalDate.now(IST).plusDays(4);
		planMeal(recipe, lunchDay, "Lunch", "360", "PLANNED");
		UUID event = planMeal(recipe, eventDay, "Event", "240", "PLANNED");
		admin.update("UPDATE meal_plans SET event_name = 'Saturday reading' WHERE id = ?", event);

		mvc.perform(authed(get("/api/v1/inventory/items/{id}", itemId)))
				.andExpect(status().isOk())
				// The reorder level is on this screen now, because taking the column off the list
				// otherwise left "why does this say Low" answerable nowhere.
				.andExpect(jsonPath("$.item.reorderThreshold").value(25))
				.andExpect(jsonPath("$.item.committed").value(30))
				.andExpect(jsonPath("$.item.available").value(20))
				.andExpect(jsonPath("$.committed.length()").value(2))
				// Planning order, which is the order the store is actually drawn down in.
				.andExpect(jsonPath("$.committed[0].planDate").value(lunchDay.toString()))
				.andExpect(jsonPath("$.committed[0].mealKind").value("Lunch"))
				.andExpect(jsonPath("$.committed[0].eventName").doesNotExist())
				.andExpect(jsonPath("$.committed[0].recipeName").value("Khichadi"))
				.andExpect(jsonPath("$.committed[0].quantity").value(18))
				.andExpect(jsonPath("$.committed[0].unit").value("KG"))
				.andExpect(jsonPath("$.committed[1].planDate").value(eventDay.toString()))
				.andExpect(jsonPath("$.committed[1].eventName").value("Saturday reading"))
				.andExpect(jsonPath("$.committed[1].quantity").value(12));
	}

	@Test
	@DisplayName("another temple's plans never commit this temple's stock")
	void committedIsTenantScoped() throws Exception {
		createItem(toorDal, "Main store", null);
		seedMovement(templeA, toorDal, UUID.randomUUID(), "50", "KG", MovementType.PO_RECEIPT, null);

		// Temple B plans a meal out of its own dal. RLS is what keeps it out of temple A's figure,
		// and nothing in the committed query names a tenant — deliberately, because a predicate here
		// would be a second opinion about a question the policy already answers.
		UUID foreignDal = insertIngredient(templeB, "Toor Dal", "KG");
		UUID foreignRecipe = insertKhichadi(templeB, foreignDal);
		planMeal(templeB, foreignRecipe, LocalDate.now(IST).plusDays(2), "Lunch", "600", "PLANNED",
				insertUser(templeB, "uid-admin-b", "admin-b@example.com", "TEMPLE_ADMIN"));

		mvc.perform(authed(get("/api/v1/inventory/items")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].committed").value(0))
				.andExpect(jsonPath("$[0].available").value(50));
	}

	// ---------------------------------------------------------------------

	/** Khichadi for temple A: 100 of yield takes 5 KG of toor dal, so the arithmetic is by eye. */
	private UUID insertKhichadi() {
		return insertKhichadi(templeA, toorDal);
	}

	private UUID insertKhichadi(UUID tenant, UUID ingredient) {
		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Rice') RETURNING id
				""", UUID.class, tenant);
		UUID recipe = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, 'Khichadi', ?, 100, 'KG') RETURNING id
				""", UUID.class, tenant, category);
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, 5, 'KG', 0)
				""", tenant, recipe, ingredient);
		return recipe;
	}

	private UUID planMeal(UUID recipe, LocalDate date, String kind, String yield, String status) {
		return planMeal(templeA, recipe, date, kind, yield, status, actorA);
	}

	private UUID planMeal(UUID tenant, UUID recipe, LocalDate date, String kind, String yield,
			String status, UUID createdBy) {
		return admin.queryForObject("""
				INSERT INTO meal_plans (
					tenant_id, plan_date, meal_kind, ready_by, recipe_id, target_yield,
					day_type, status, created_by)
				VALUES (?, ?, ?, TIME '12:00', ?, ?::numeric, 'REGULAR', ?, ?)
				RETURNING id
				""", UUID.class, tenant, date, kind, recipe, yield, status, createdBy);
	}

	// ---------------------------------------------------------------------

	private UUID createItem(UUID ingredientId, String location, String threshold) throws Exception {
		String body = mvc.perform(createRequest(ingredientId, location, threshold))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
	}

	private MockHttpServletRequestBuilder createRequest(UUID ingredientId, String location, String threshold) {
		StringBuilder json = new StringBuilder("{\"ingredientId\":\"").append(ingredientId).append("\"");
		if (location != null) {
			json.append(",\"storageLocation\":\"").append(location).append("\"");
		}
		if (threshold != null) {
			json.append(",\"reorderThreshold\":").append(threshold);
		}
		json.append("}");
		return authed(post("/api/v1/inventory/items"))
				.contentType(MediaType.APPLICATION_JSON).content(json.toString());
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private void seedMovement(UUID tenant, UUID ingredient, UUID batch, String qty, String unit,
			MovementType type, LocalDate expiry) {
		admin.update("""
				INSERT INTO stock_movements (
					tenant_id, ingredient_id, batch_id, quantity, unit, movement_type,
					expiry_date, received_date, actor_user_id)
				VALUES (?, ?, ?, ?::numeric, ?, ?, ?, ?, ?)
				""", tenant, ingredient, batch, qty, unit, type.name(), expiry, expiry == null ? null : expiry, actorA);
	}

	private void assertAudit(String action, int expected) {
		Integer c = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = ?", Integer.class, action);
		org.assertj.core.api.Assertions.assertThat(c).isEqualTo(expected);
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
				VALUES (?, ?, 'Pulses', ?)
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
