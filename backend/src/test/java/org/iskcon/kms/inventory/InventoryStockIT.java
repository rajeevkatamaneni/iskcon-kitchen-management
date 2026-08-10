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
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM audit_events");
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
				.andExpect(jsonPath("$.code").value("KMS-4909"));
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
				.andExpect(jsonPath("$.code").value("KMS-4402"));
	}

	@Test
	@DisplayName("a volunteer cannot see the stock view")
	void volunteerCannotView() throws Exception {
		signIn("uid-vol-a");
		mvc.perform(authed(get("/api/v1/inventory/items"))).andExpect(status().isForbidden());
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
