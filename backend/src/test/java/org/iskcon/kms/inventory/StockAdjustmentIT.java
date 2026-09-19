package org.iskcon.kms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Manual stock adjustment (E3-S7) through the full stack: a signed ADJUSTMENT movement with a
 * mandatory reason, the negative-stock guard, the large-adjustment approval split, and the audit
 * trail that a large write-off leaves behind.
 */
@AutoConfigureMockMvc
class StockAdjustmentIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID actorA;
	private UUID itemId;
	private UUID ingredientId;
	private UUID batch;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		actorA = insertUser(templeA, "uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");
		insertUser(templeA, "uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		ingredientId = insertIngredient(templeA, "Toor Dal", "KG");
		itemId = admin.queryForObject("""
				INSERT INTO inventory_items (tenant_id, ingredient_id, storage_location)
				VALUES (?, ?, 'Main store') RETURNING id
				""", UUID.class, templeA, ingredientId);
		// A 100 KG batch on hand.
		batch = UUID.randomUUID();
		seedReceipt(batch, "100");
		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM audit_events");
		// A count that adds stock now sets the market rate, and its append-only history holds the
		// ingredient down.
		admin.execute("DELETE FROM ingredient_market_rate_history");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("kitchen staff can make a small adjustment; it lands in the ledger but not the audit log")
	void smallAdjustmentByStaff() throws Exception {
		mvc.perform(adjust(batch, "-3", "KG", "SPOILAGE", null)).andExpect(status().isCreated());

		assertThat(batchStock()).isEqualByComparingTo("97");
		assertThat(auditCount("STOCK_ADJUSTED")).as("small adjustments live in the ledger alone").isZero();
	}

	@Test
	@DisplayName("a large adjustment is refused for kitchen staff, allowed for an admin, and audited")
	void largeAdjustmentNeedsAdmin() throws Exception {
		// 30 KG of 100 is 30% — over the 20% line.
		mvc.perform(adjust(batch, "-30", "KG", "DAMAGE", null))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-400025"));
		assertThat(batchStock()).as("nothing was written on the refusal").isEqualByComparingTo("100");

		signIn("uid-admin-a");
		mvc.perform(adjust(batch, "-30", "KG", "DAMAGE", null)).andExpect(status().isCreated());
		assertThat(batchStock()).isEqualByComparingTo("70");
		assertThat(auditCount("STOCK_ADJUSTED")).isEqualTo(1);
	}

	@Test
	@DisplayName("an adjustment cannot take a batch below zero")
	void cannotGoNegative() throws Exception {
		signIn("uid-admin-a"); // admin, so the large-approval gate isn't what stops it
		mvc.perform(adjust(batch, "-150", "KG", "COUNT_CORRECTION", null))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400041"));
		assertThat(batchStock()).isEqualByComparingTo("100");
	}

	@Test
	@DisplayName("an item with no batches can be told what is on the shelf, and it opens one")
	void openingCountOnAnItemWithNothingInTheLedger() throws Exception {
		// A consumable somebody has only just started tracking: the sacks are in the store and the
		// ledger has never heard of them. Every other way in — a purchase-order receipt, a donation —
		// describes stock *arriving*, so this item sat at zero, badged "below reorder level", with
		// nothing on the screen that could answer it. Coconut, on the live site, 2026-08-23.
		UUID coconutIngredient = insertIngredient(templeA, "Coconut", "PIECES");
		UUID coconut = admin.queryForObject("""
				INSERT INTO inventory_items (tenant_id, ingredient_id, storage_location)
				VALUES (?, ?, 'Main store') RETURNING id
				""", UUID.class, templeA, coconutIngredient);

		// The first count of an item holding nothing cannot be sized as a fraction of what it holds,
		// so it is always a large adjustment — a second signature on the opening figure, which is the
		// right rule for the one number every later figure is measured against.
		signIn("uid-admin-a");
		mvc.perform(post("/api/v1/inventory/items/{id}/adjustments", coconut)
						.header("Authorization", "Bearer valid-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"batchId":null,"quantity":40,"unit":"PIECES","reason":"COUNT_CORRECTION",
								 "pricePerUnit":25}
								"""))
				.andExpect(status().isCreated());

		assertThat(admin.queryForObject(
				"SELECT COALESCE(SUM(quantity), 0) FROM stock_movements WHERE ingredient_id = ?",
				BigDecimal.class, coconutIngredient)).isEqualByComparingTo("40");

		// And it opened a batch of its own, so the next spoilage has a lot to be written off against.
		assertThat(admin.queryForObject(
				"SELECT COUNT(DISTINCT batch_id) FROM stock_movements WHERE ingredient_id = ?",
				Integer.class, coconutIngredient)).isEqualTo(1);
	}

	@Test
	@DisplayName("an opening count cannot be negative — that is not a count of anything")
	void openingCountCannotBeNegative() throws Exception {
		signIn("uid-admin-a");
		mvc.perform(adjust(null, "-5", "KG", "COUNT_CORRECTION", null))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("a small correction upward adds to the batch")
	void smallCorrectionUpward() throws Exception {
		mvc.perform(adjust(batch, "2", "KG", "COUNT_CORRECTION", null, "110"))
				.andExpect(status().isCreated());
		assertThat(batchStock()).isEqualByComparingTo("102");
	}

	// ---- What it would cost to buy today (R-ING-3, T-254) ---------------------------------------

	@Test
	@DisplayName("an opening count with no value is refused with KMS-400161, and writes nothing")
	void openingCountNeedsAValue() throws Exception {
		signIn("uid-admin-a"); // admin, so the large-approval gate isn't what stops it
		mvc.perform(adjust(null, "40", "KG", "COUNT_CORRECTION", null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400161"));
		assertThat(movementCount()).as("only the seeded receipt").isEqualTo(1);
		assertThat(marketRate()).isNull();
		assertThat(historyCount()).isZero();
	}

	/**
	 * "It can't be blank or 0" — and a negative is no more a price than zero is. A rate so small it
	 * rounds to nothing at the column's four places is zero too.
	 */
	@Test
	@DisplayName("an opening count valued at 0, below 0 or at a rounding-to-nothing figure is refused")
	void openingCountValueCannotBeZero() throws Exception {
		signIn("uid-admin-a");
		for (String price : new String[] {"0", "-5", "0.00001"}) {
			mvc.perform(adjust(null, "40", "KG", "COUNT_CORRECTION", null, price))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("KMS-400161"));
		}
		assertThat(movementCount()).isEqualTo(1);
		assertThat(marketRate()).isNull();
	}

	/**
	 * The opening count's value becomes the market rate, as a STOCK_TAKE, with one history row naming
	 * who set it. Typed per canonical unit (₹ per Kg) even when the count itself is in grams: the box
	 * is "₹ per Kg", and the count's unit says nothing about the price's.
	 */
	@Test
	@DisplayName("an opening count's value sets the market rate, per stock unit, with its history")
	void openingCountSetsTheMarketRate() throws Exception {
		signIn("uid-admin-a");
		mvc.perform(adjust(null, "40000", "GM", "COUNT_CORRECTION", null, "118.5"))
				.andExpect(status().isCreated());

		assertThat(marketRate()).isEqualByComparingTo("118.5");
		assertThat(admin.queryForObject(
				"SELECT market_rate_source FROM ingredients WHERE id = ?", String.class, ingredientId))
				.isEqualTo("STOCK_TAKE");
		assertThat(historyCount()).isEqualTo(1);
		assertThat(admin.queryForMap("""
				SELECT h.rate, h.source, h.tenant_id, u.firebase_uid
				FROM ingredient_market_rate_history h JOIN users u ON u.id = h.set_by
				WHERE h.ingredient_id = ?
				""", ingredientId))
				.containsEntry("source", "STOCK_TAKE")
				.containsEntry("tenant_id", templeA)
				.containsEntry("firebase_uid", "uid-admin-a");
	}

	/**
	 * The conductor's ruling (2026-09-19): a person correcting an existing batch upwards is adding
	 * stock by a count too, so the value is required there as well.
	 */
	@Test
	@DisplayName("a count correction that adds to an existing batch needs a value too")
	void correctionUpwardNeedsAValue() throws Exception {
		mvc.perform(adjust(batch, "2", "KG", "COUNT_CORRECTION", null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400161"));
		assertThat(batchStock()).isEqualByComparingTo("100");

		mvc.perform(adjust(batch, "2", "KG", "COUNT_CORRECTION", null, "110"))
				.andExpect(status().isCreated());
		assertThat(marketRate()).isEqualByComparingTo("110");
	}

	/**
	 * The conductor's second ruling (2026-09-19): a person adding stock is asked its value whatever
	 * reason they pick. The reason is a word from a dropdown; the rule is about stock arriving on the
	 * books without a price. One run per reason: +2 Kg on the 100 Kg batch (small, so kitchen staff
	 * may make it), refused without a value and nothing written, then saved with one, which becomes
	 * the market rate as a STOCK_TAKE.
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(AdjustmentReason.class)
	@DisplayName("a positive adjustment for any reason needs a value, and saving it sets the market rate")
	void everyPositiveReasonNeedsAValue(AdjustmentReason reason) throws Exception {
		String note = reason == AdjustmentReason.OTHER ? "Found a sack behind the dal" : null;

		mvc.perform(adjust(batch, "2", "KG", reason.name(), note))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400161"));
		assertThat(batchStock()).as("nothing was written on the refusal").isEqualByComparingTo("100");
		assertThat(marketRate()).isNull();

		mvc.perform(adjust(batch, "2", "KG", reason.name(), note, "112"))
				.andExpect(status().isCreated());
		assertThat(batchStock()).isEqualByComparingTo("102");
		assertThat(marketRate()).isEqualByComparingTo("112");
		assertThat(admin.queryForObject(
				"SELECT market_rate_source FROM ingredients WHERE id = ?", String.class, ingredientId))
				.isEqualTo("STOCK_TAKE");
		assertThat(historyCount()).isEqualTo(1);
	}

	/** Taking stock away asks nothing about its value: nothing is arriving on the books. */
	@Test
	@DisplayName("a correction downward and a write-off need no value, and leave the market rate alone")
	void takingStockAwayNeedsNoValue() throws Exception {
		mvc.perform(adjust(batch, "-2", "KG", "COUNT_CORRECTION", null)).andExpect(status().isCreated());
		mvc.perform(adjust(batch, "-1", "KG", "SPOILAGE", null)).andExpect(status().isCreated());
		assertThat(batchStock()).isEqualByComparingTo("97");
		assertThat(marketRate()).isNull();
		assertThat(historyCount()).isZero();
	}

	/**
	 * A count that is refused sets no rate, even with a good value on it: the rate is written only
	 * after the movement, in the same transaction. Here kitchen staff try an opening count, which is
	 * always a large adjustment and needs an admin.
	 */
	@Test
	@DisplayName("a count refused for another reason sets no market rate")
	void refusedCountSetsNoRate() throws Exception {
		mvc.perform(adjust(null, "40", "KG", "COUNT_CORRECTION", null, "118"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-400025"));
		assertThat(marketRate()).isNull();
		assertThat(historyCount()).isZero();
	}

	@Test
	@DisplayName("the reason OTHER requires a note")
	void otherReasonNeedsNote() throws Exception {
		mvc.perform(adjust(batch, "-1", "KG", "OTHER", null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));
	}

	@Test
	@DisplayName("the adjustment unit must belong to the ingredient's measurement family")
	void unitMustMatchFamily() throws Exception {
		// The one rule every quantity obeys (BL-9, T-150): a unit from another family is
		// KMS-400013 naming the ingredient, not the generic "check your input".
		mvc.perform(adjust(batch, "-1", "L", "SPOILAGE", null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400013"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("Toor Dal"));
		assertThat(batchStock()).as("nothing was written on the refusal").isEqualByComparingTo("100");
	}

	@Test
	@DisplayName("adjusting a batch that doesn't exist is a not-found")
	void unknownBatch() throws Exception {
		mvc.perform(adjust(UUID.randomUUID(), "-1", "KG", "SPOILAGE", null))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-400030"));
	}

	// ---------------------------------------------------------------------

	private MockHttpServletRequestBuilder adjust(
			UUID batchId, String qty, String unit, String reason, String note) {
		return adjust(batchId, qty, unit, reason, note, null);
	}

	private MockHttpServletRequestBuilder adjust(
			UUID batchId, String qty, String unit, String reason, String note, String pricePerUnit) {
		StringBuilder json = new StringBuilder("{")
				.append(batchId == null
						? "\"batchId\":null,"
						: "\"batchId\":\"" + batchId + "\",")
				.append("\"quantity\":").append(qty).append(",")
				.append("\"unit\":\"").append(unit).append("\",")
				.append("\"reason\":\"").append(reason).append("\"");
		if (note != null) {
			json.append(",\"note\":\"").append(note).append("\"");
		}
		if (pricePerUnit != null) {
			json.append(",\"pricePerUnit\":").append(pricePerUnit);
		}
		json.append("}");
		return post("/api/v1/inventory/items/{id}/adjustments", itemId)
				.header("Authorization", "Bearer valid-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.toString());
	}

	private BigDecimal batchStock() {
		return admin.queryForObject(
				"SELECT COALESCE(SUM(quantity), 0) FROM stock_movements WHERE batch_id = ?",
				BigDecimal.class, batch);
	}

	private BigDecimal marketRate() {
		return admin.queryForObject(
				"SELECT market_rate FROM ingredients WHERE id = ?", BigDecimal.class, ingredientId);
	}

	private int historyCount() {
		Integer c = admin.queryForObject(
				"SELECT count(*) FROM ingredient_market_rate_history WHERE ingredient_id = ?",
				Integer.class, ingredientId);
		return c == null ? 0 : c;
	}

	private int movementCount() {
		Integer c = admin.queryForObject(
				"SELECT count(*) FROM stock_movements WHERE ingredient_id = ?", Integer.class, ingredientId);
		return c == null ? 0 : c;
	}

	private void seedReceipt(UUID batchId, String qty) {
		admin.update("""
				INSERT INTO stock_movements (
					tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
				VALUES (?, ?, ?, ?::numeric, 'KG', 'PO_RECEIPT', ?)
				""", templeA, ingredientId, batchId, qty, actorA);
	}

	private int auditCount(String action) {
		Integer c = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = ?", Integer.class, action);
		return c == null ? 0 : c;
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

}
