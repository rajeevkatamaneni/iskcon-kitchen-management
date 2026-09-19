package org.iskcon.kms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * "Add to inventory" with its opening count, in one request and one transaction (T-294).
 *
 * <p>The defect this closes (VERIFY-A defect 5): the page used to create the item, then send the
 * count as a second request. When the second one failed, the item was left behind with no stock and
 * no value, and it vanished from the Add list, so nobody could simply try again. The count now rides
 * on the create request and goes through the adjustment's own code in the same transaction.
 *
 * <p>So the tests that matter are the refusals, and what each one checks is the <em>absence</em> of
 * everything: no item, no movement, no market rate moved, no market rate history, no audit row. Each
 * refusal here happens after the item row and its INVENTORY_ITEM_ADDED audit row were already
 * inserted, so a clean table proves a rollback, not merely a check that ran first. The ingredient
 * starts with a market rate of ₹50 so "the rate did not change" is a real comparison, not null
 * against null.
 */
@AutoConfigureMockMvc
class InventoryOpeningCountIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID templeA;
	private UUID rice;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		templeA = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		insertUser("uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");
		insertUser("uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		insertUser("uid-manager-a", "manager-a@example.com", "KITCHEN_MANAGER");
		insertUser("uid-volunteer-a", "volunteer-a@example.com", "VOLUNTEER");
		rice = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit,
					market_rate, market_rate_on, market_rate_source)
				VALUES (?, 'Sona Masoori rice', 'Grains', 'KG', 50, DATE '2026-09-01', 'MANUAL')
				RETURNING id
				""", UUID.class, templeA);
		stubVerifier.accept("uid-admin-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM ingredient_market_rate_history");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- The happy path --------------------------------------------------

	@Test
	@DisplayName("the item and its opening count are written together, with the note, the value and the audit")
	void itemAndOpeningCountTogether() throws Exception {
		mvc.perform(create("""
						{"ingredientId":"%s","storageLocation":"Main store",
						 "openingCount":{"quantity":40,"unit":"KG","pricePerUnit":62}}
						""".formatted(rice)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNotEmpty());

		assertThat(count("inventory_items")).isEqualTo(1);

		// One movement: the item's first lot, recorded exactly as the item page's first count is.
		Map<String, Object> movement = admin.queryForMap("""
				SELECT quantity, unit, movement_type, reason_category, note, batch_id
				FROM stock_movements WHERE ingredient_id = ?
				""", rice);
		assertThat((BigDecimal) movement.get("quantity")).isEqualByComparingTo("40");
		assertThat(movement.get("unit")).isEqualTo("KG");
		assertThat(movement.get("movement_type")).isEqualTo("ADJUSTMENT");
		assertThat(movement.get("reason_category")).isEqualTo("COUNT_CORRECTION");
		assertThat(movement.get("note")).isEqualTo("Opening count, when the item was added to inventory.");
		assertThat(movement.get("batch_id")).as("it opened a lot of its own").isNotNull();

		// The value became the market rate, as a stock-take, with its history row.
		assertThat(marketRate()).isEqualByComparingTo("62");
		assertThat(admin.queryForObject(
				"SELECT market_rate_source FROM ingredients WHERE id = ?", String.class, rice))
				.isEqualTo("STOCK_TAKE");
		assertThat(historyCount()).isEqualTo(1);

		// The item's own audit row, and — a first count being a large adjustment — the adjustment's.
		assertThat(auditCount("INVENTORY_ITEM_ADDED")).isEqualTo(1);
		assertThat(auditCount("STOCK_ADJUSTED")).isEqualTo(1);
	}

	@Test
	@DisplayName("with no opening count the item is added on its own, exactly as before")
	void noOpeningCount() throws Exception {
		mvc.perform(create("""
						{"ingredientId":"%s","storageLocation":"Main store","openingCount":null}
						""".formatted(rice)))
				.andExpect(status().isCreated());

		assertThat(count("inventory_items")).isEqualTo(1);
		assertThat(count("stock_movements")).isZero();
		assertThat(marketRate()).isEqualByComparingTo("50");
		assertThat(historyCount()).isZero();
		assertThat(auditCount("INVENTORY_ITEM_ADDED")).isEqualTo(1);
	}

	// ---- Any failure leaves nothing ----------------------------------------

	@Test
	@DisplayName("a count with no value is refused with KMS-400161, and the item is not left behind")
	void noValueLeavesNothing() throws Exception {
		mvc.perform(create("""
						{"ingredientId":"%s","storageLocation":"Main store",
						 "openingCount":{"quantity":40,"unit":"KG"}}
						""".formatted(rice)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400161"));

		assertNothingWritten();
	}

	@Test
	@DisplayName("a count valued at 0 is refused with KMS-400161, and the item is not left behind")
	void zeroValueLeavesNothing() throws Exception {
		mvc.perform(create("""
						{"ingredientId":"%s","storageLocation":"Main store",
						 "openingCount":{"quantity":40,"unit":"KG","pricePerUnit":0}}
						""".formatted(rice)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400161"));

		assertNothingWritten();
	}

	@Test
	@DisplayName("a count in a unit from the wrong family is refused, and the item is not left behind")
	void wrongUnitLeavesNothing() throws Exception {
		mvc.perform(create("""
						{"ingredientId":"%s","storageLocation":"Main store",
						 "openingCount":{"quantity":40,"unit":"L","pricePerUnit":62}}
						""".formatted(rice)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400013"));

		assertNothingWritten();
	}

	// ---- Who may enter a first count (T-305) --------------------------------
	//
	// Rajeev, Decisions Desk, 2026-09-19 (Q-22): "The first count on a new item isn't an adjustment:
	// anyone who manages inventory can enter it. Later corrections keep the 20% rule." Before T-305
	// this class had a test asserting the opposite — Kitchen Staff refused KMS-400025 on a first
	// count — and it is replaced by the two below, not kept alongside them.

	@Test
	@DisplayName("kitchen staff can add a new item with its first count: stock, value and market rate all set")
	void kitchenStaffMayEnterFirstCount() throws Exception {
		stubVerifier.accept("uid-staff-a");
		assertFirstCountAccepted();
	}

	@Test
	@DisplayName("a kitchen manager can add a new item with its first count: stock, value and market rate all set")
	void kitchenManagerMayEnterFirstCount() throws Exception {
		stubVerifier.accept("uid-manager-a");
		assertFirstCountAccepted();
	}

	@Test
	@DisplayName("a volunteer cannot add to inventory at all, and nothing is written")
	void volunteerRefused() throws Exception {
		stubVerifier.accept("uid-volunteer-a");
		mvc.perform(create("""
						{"ingredientId":"%s","storageLocation":"Main store",
						 "openingCount":{"quantity":40,"unit":"KG","pricePerUnit":62}}
						""".formatted(rice)))
				.andExpect(status().isForbidden());

		assertNothingWritten();
	}

	/**
	 * "Later corrections keep the 20% rule" — and an existing item holding nothing is the case most
	 * likely to be mistaken for a first count. The item was added without a count, so it has no
	 * stock; Kitchen Staff then counting 40 kg on its page is a correction, and still needs a
	 * Temple Admin.
	 */
	@Test
	@DisplayName("kitchen staff counting a large amount onto an existing item at zero is still refused with KMS-400025")
	void existingItemAtZeroKeepsTheRule() throws Exception {
		stubVerifier.accept("uid-staff-a");
		UUID itemId = createItem("""
				{"ingredientId":"%s","storageLocation":"Main store","openingCount":null}
				""".formatted(rice));

		mvc.perform(adjust(itemId, """
						{"quantity":40,"unit":"KG","reason":"COUNT_CORRECTION","pricePerUnit":62}
						"""))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-400025"));

		assertThat(count("stock_movements")).as("no movement").isZero();
		assertThat(marketRate()).as("market rate unchanged").isEqualByComparingTo("50");
		assertThat(historyCount()).isZero();
	}

	/**
	 * The narrow reading of "a new item": removing an item keeps its ledger, so an ingredient that
	 * was tracked, run down to zero, removed and added back is not new. Its first count on the
	 * re-add is a correction of that history and keeps the 20% rule — and, being refused, it takes
	 * the re-added item with it, as every refusal of an opening count does.
	 */
	@Test
	@DisplayName("re-adding an item whose ingredient has ledger history keeps the 20% rule, and nothing is left behind")
	void readdedItemKeepsTheRule() throws Exception {
		// The admin tracks rice, counts 40 kg, writes it all off, and stops tracking it.
		UUID first = createItem("""
				{"ingredientId":"%s","openingCount":{"quantity":40,"unit":"KG","pricePerUnit":62}}
				""".formatted(rice));
		UUID batch = admin.queryForObject(
				"SELECT batch_id FROM stock_movements WHERE ingredient_id = ?", UUID.class, rice);
		mvc.perform(adjust(first, """
						{"batchId":"%s","quantity":-40,"unit":"KG","reason":"SPOILAGE"}
						""".formatted(batch)))
				.andExpect(status().isCreated());
		mvc.perform(delete("/api/v1/inventory/items/" + first).header("Authorization", "Bearer valid-token"))
				.andExpect(status().isNoContent());
		int movementsBefore = count("stock_movements");
		BigDecimal rateBefore = marketRate();

		stubVerifier.accept("uid-staff-a");
		mvc.perform(create("""
						{"ingredientId":"%s","openingCount":{"quantity":40,"unit":"KG","pricePerUnit":70}}
						""".formatted(rice)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("KMS-400025"));

		assertThat(count("inventory_items")).as("the re-added item was rolled back").isZero();
		assertThat(count("stock_movements")).isEqualTo(movementsBefore);
		assertThat(marketRate()).isEqualByComparingTo(rateBefore);
	}

	private void assertFirstCountAccepted() throws Exception {
		mvc.perform(create("""
						{"ingredientId":"%s","storageLocation":"Main store",
						 "openingCount":{"quantity":40,"unit":"KG","pricePerUnit":62}}
						""".formatted(rice)))
				.andExpect(status().isCreated());

		assertThat(count("inventory_items")).isEqualTo(1);
		BigDecimal onHand = admin.queryForObject(
				"SELECT SUM(quantity) FROM stock_movements WHERE ingredient_id = ?", BigDecimal.class, rice);
		assertThat(onHand).as("stock").isEqualByComparingTo("40");
		assertThat(admin.queryForObject(
				"SELECT note FROM stock_movements WHERE ingredient_id = ?", String.class, rice))
				.isEqualTo("Opening count, when the item was added to inventory.");
		assertThat(marketRate()).as("the value became the market rate").isEqualByComparingTo("62");
		assertThat(admin.queryForObject(
				"SELECT market_rate_source FROM ingredients WHERE id = ?", String.class, rice))
				.isEqualTo("STOCK_TAKE");
		assertThat(historyCount()).isEqualTo(1);
		// Nobody signs it, but who entered it is still on the record.
		assertThat(auditCount("STOCK_ADJUSTED")).isEqualTo(1);
	}

	private UUID createItem(String json) throws Exception {
		mvc.perform(create(json)).andExpect(status().isCreated());
		return admin.queryForObject(
				"SELECT id FROM inventory_items WHERE ingredient_id = ?", UUID.class, rice);
	}

	private MockHttpServletRequestBuilder adjust(UUID itemId, String json) {
		return post("/api/v1/inventory/items/" + itemId + "/adjustments")
				.header("Authorization", "Bearer valid-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json);
	}

	@Test
	@DisplayName("after a refusal the same add succeeds, because nothing was left in the way")
	void refusalCanBeRetried() throws Exception {
		mvc.perform(create("""
						{"ingredientId":"%s","openingCount":{"quantity":40,"unit":"KG"}}
						""".formatted(rice)))
				.andExpect(status().isBadRequest());

		mvc.perform(create("""
						{"ingredientId":"%s","openingCount":{"quantity":40,"unit":"KG","pricePerUnit":62}}
						""".formatted(rice)))
				.andExpect(status().isCreated());
		assertThat(count("inventory_items")).isEqualTo(1);
		assertThat(count("stock_movements")).isEqualTo(1);
	}

	// ---------------------------------------------------------------------

	private void assertNothingWritten() {
		assertThat(count("inventory_items")).as("no item").isZero();
		assertThat(count("stock_movements")).as("no movement").isZero();
		assertThat(marketRate()).as("market rate unchanged").isEqualByComparingTo("50");
		assertThat(admin.queryForObject(
				"SELECT market_rate_source FROM ingredients WHERE id = ?", String.class, rice))
				.isEqualTo("MANUAL");
		assertThat(historyCount()).as("no market rate history").isZero();
		assertThat(count("audit_events")).as("no audit row of any kind").isZero();
	}

	private MockHttpServletRequestBuilder create(String json) {
		return post("/api/v1/inventory/items")
				.header("Authorization", "Bearer valid-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json);
	}

	private int count(String table) {
		Integer c = admin.queryForObject("SELECT count(*) FROM " + table, Integer.class);
		return c == null ? 0 : c;
	}

	private BigDecimal marketRate() {
		return admin.queryForObject("SELECT market_rate FROM ingredients WHERE id = ?", BigDecimal.class, rice);
	}

	private int historyCount() {
		Integer c = admin.queryForObject(
				"SELECT count(*) FROM ingredient_market_rate_history WHERE ingredient_id = ?", Integer.class, rice);
		return c == null ? 0 : c;
	}

	private int auditCount(String action) {
		Integer c = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = ?", Integer.class, action);
		return c == null ? 0 : c;
	}

	private void insertUser(String uid, String email, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				""", templeA, uid, email, role);
	}
}
