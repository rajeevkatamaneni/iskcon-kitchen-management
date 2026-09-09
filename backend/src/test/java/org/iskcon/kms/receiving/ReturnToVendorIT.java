package org.iskcon.kms.receiving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.inventory.MovementType;
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
 * Returning goods to a vendor after they were already accepted (T-013).
 *
 * <p>Rejection only ever worked at the gate, so weevils found the next morning — or fifty kilos
 * keyed when five arrived — had no path at all. What is proved here is the three rules the feature
 * rests on: the stock comes off through a movement like everything else, the goods receipt is never
 * mutated, and nothing can go back that did not come in.
 *
 * <p>And a fourth, which is not about returning at all: <strong>the {@code CHECK} constraint on
 * {@code stock_movements.movement_type} and {@link MovementType} agree.</strong> The enum is
 * mirrored in the database, and a value added on one side alone fails at runtime rather than at
 * compile time — on the day somebody presses the button, not on the day the enum was edited. So the
 * new value is really inserted here, and both sets are compared in both directions.
 */
@AutoConfigureMockMvc
@Import(ReturnToVendorIT.StubVerifierConfiguration.class)
class ReturnToVendorIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private static final ObjectMapper JSON = new ObjectMapper();

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID staffId;
	private UUID rice;
	private UUID vendor;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		// KITCHEN_STAFF, and deliberately the weakest role that can do this: returning goods is
		// behind MANAGE_INVENTORY, which is the store room's permission and not the buyer's.
		staffId = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-staff-a', 'Staff A', 'staff-a@example.com', '+919876500081', 'KITCHEN_STAFF', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);
		rice = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Rice', 'Grains', 'KG') RETURNING id
				""", UUID.class, tenant);
		vendor = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Govind Wholesale', '+919812345678')
				RETURNING id
				""", UUID.class, tenant);
		stubVerifier.accept("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		// goods_returns first: it holds a RESTRICT reference to the ledger row it booked.
		admin.execute("DELETE FROM goods_returns");
		admin.execute("DELETE FROM goods_receipt_lines");
		admin.execute("DELETE FROM goods_receipts");
		admin.execute("DELETE FROM shopping_list_lines");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM po_events");
		admin.execute("DELETE FROM purchase_order_lines");
		admin.execute("DELETE FROM purchase_orders");
		admin.execute("DELETE FROM po_sequence");
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("the CHECK constraint and MovementType agree, proven by inserting every value including the new one")
	void theLedgerAcceptsEveryMovementTypeTheEnumDeclares() {
		// Both directions. A value the enum has and the constraint refuses fails the insert below;
		// a value the constraint allows and the enum has never heard of fails the comparison after
		// it. Either way the failure is here, in a test, and not in production on the first return.
		for (MovementType type : MovementType.values()) {
			UUID id = UUID.randomUUID();
			admin.update("""
					INSERT INTO stock_movements (
						id, tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
					VALUES (?, ?, ?, ?, -1, 'KG', ?, ?)
					""", id, tenant, rice, UUID.randomUUID(), type.name(), staffId);
			assertThat(admin.queryForObject(
					"SELECT movement_type FROM stock_movements WHERE id = ?", String.class, id))
					.as("the ledger must accept %s", type)
					.isEqualTo(type.name());
		}

		String definition = admin.queryForObject("""
				SELECT pg_get_constraintdef(oid) FROM pg_constraint
				WHERE conname = 'stock_movements_type_valid'
				""", String.class);
		Set<String> inConstraint = new TreeSet<>();
		Matcher m = Pattern.compile("'([A-Z_]+)'").matcher(definition == null ? "" : definition);
		while (m.find()) {
			inConstraint.add(m.group(1));
		}
		Set<String> inEnum = Arrays.stream(MovementType.values())
				.map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
		assertThat(inConstraint)
				.as("the CHECK and the Java enum are one vocabulary written twice")
				.isEqualTo(inEnum);
	}

	@Test
	@DisplayName("returning 5 of 30 takes 5 off stock through a RETURN_TO_VENDOR movement on the receipt's own batch")
	void aReturnReducesStockThroughAMovement() throws Exception {
		Receipt receipt = receive("PO-2026-0201", "30");
		assertThat(onHand()).isEqualByComparingTo("30");

		mvc.perform(returnGoods(receipt.receiptId, body("r1", receipt.lineId, "5", "SPOILED", "Weevils in two sacks")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.quantity").value(5))
				.andExpect(jsonPath("$.reason").value("SPOILED"))
				.andExpect(jsonPath("$.ingredientName").value("Rice"))
				.andExpect(jsonPath("$.stockMovementId").exists());

		// Exactly five, and through the ledger — which is what every reader of on-hand sums.
		assertThat(onHand()).as("the returned five must leave stock").isEqualByComparingTo("25");

		Map<String, Object> movement = admin.queryForMap("""
				SELECT quantity, movement_type, batch_id, reference_type, reference_id
				FROM stock_movements WHERE movement_type = 'RETURN_TO_VENDOR'
				""");
		assertThat((BigDecimal) movement.get("quantity"))
				.as("negative in the ledger, positive on the return record")
				.isEqualByComparingTo("-5");
		// The batch the receipt established, not the ingredient at large: FEFO reads per batch, and
		// a return that missed the batch would leave the sacks it came from looking full.
		assertThat(movement.get("batch_id")).isEqualTo(receipt.batchId);
		assertThat(movement.get("reference_type")).isEqualTo("PURCHASE_ORDER");
		assertThat(movement.get("reference_id")).isEqualTo(receipt.poId);
		assertThat(batchStock(receipt.batchId)).isEqualByComparingTo("25");
	}

	@Test
	@DisplayName("the goods receipt line is byte-for-byte what it was before the return")
	void theReceiptItselfIsNeverMutated() throws Exception {
		Receipt receipt = receive("PO-2026-0202", "30");
		String before = receiptLineAsText(receipt.lineId);

		mvc.perform(returnGoods(receipt.receiptId, body("r1", receipt.lineId, "12", "DAMAGED", null)))
				.andExpect(status().isCreated());

		// The whole row rendered as text, so a change to any column at all shows up — not only to
		// the two somebody would think to assert on.
		assertThat(receiptLineAsText(receipt.lineId))
				.as("a receipt says what was signed for on the day and is never edited")
				.isEqualTo(before);

		// What did change is derived, and read back on the receipt without being stored on it.
		mvc.perform(authed(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.get("/api/v1/purchase-orders/{poId}/receipts", receipt.poId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].lines[0].receivedQty").value(30))
				.andExpect(jsonPath("$[0].lines[0].returnedQty").value(12));
	}

	@Test
	@DisplayName("returning more than was received is refused with KMS-400140, and writes nothing")
	void cannotReturnMoreThanWasReceived() throws Exception {
		Receipt receipt = receive("PO-2026-0203", "30");

		mvc.perform(returnGoods(receipt.receiptId, body("r1", receipt.lineId, "31", "WRONG_ITEM", null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400140"));

		assertThat(onHand()).as("a refused return moves no stock").isEqualByComparingTo("30");
		assertThat(returnCount()).isZero();
		assertThat(movementCount("RETURN_TO_VENDOR")).isZero();
	}

	@Test
	@DisplayName("the cap is cumulative: 20 back then 11 more is refused, 20 then 10 is not")
	void theCapCountsEveryReturnAlreadyMade() throws Exception {
		Receipt receipt = receive("PO-2026-0204", "30");

		mvc.perform(returnGoods(receipt.receiptId, body("r1", receipt.lineId, "20", "SPOILED", null)))
				.andExpect(status().isCreated());
		// 20 + 11 is 31 against a 30 line. Each request on its own is under the received quantity,
		// which is exactly why the check has to look at the sum and not at the request.
		mvc.perform(returnGoods(receipt.receiptId, body("r2", receipt.lineId, "11", "SPOILED", null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400140"));
		mvc.perform(returnGoods(receipt.receiptId, body("r3", receipt.lineId, "10", "SPOILED", null)))
				.andExpect(status().isCreated());

		assertThat(onHand()).as("30 in, 30 back").isEqualByComparingTo("0");
		assertThat(returnCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("a fifty-for-five keying error goes back in full even though only five were ever there")
	void aPhantomQuantityCanStillBeReturned() throws Exception {
		Receipt receipt = receive("PO-2026-0205", "50");
		// Five real kilos were cooked; the other forty-five never existed. The ledger says 45.
		admin.update("""
				INSERT INTO stock_movements (
					tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
				VALUES (?, ?, ?, -5, 'KG', 'CONSUMPTION', ?)
				""", tenant, rice, receipt.batchId, staffId);
		assertThat(onHand()).isEqualByComparingTo("45");

		// On-hand is deliberately not a gate. Refusing this because the shelf would go negative
		// would leave the overstatement standing for ever, which is the defect and not the guard.
		mvc.perform(returnGoods(receipt.receiptId, body("r1", receipt.lineId, "45", "NOT_DELIVERED", "50 keyed, 5 arrived")))
				.andExpect(status().isCreated());

		assertThat(onHand()).as("50 in, 5 eaten, 45 that never came taken back out")
				.isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("a repeated submission under the same key returns the first return and withdraws nothing more")
	void aDuplicateSubmissionCannotDoubleWithdraw() throws Exception {
		Receipt receipt = receive("PO-2026-0206", "30");
		String json = body("same-key", receipt.lineId, "6", "SPOILED", null);

		String first = mvc.perform(returnGoods(receipt.receiptId, json))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String again = mvc.perform(returnGoods(receipt.receiptId, json))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

		assertThat(idOf(again)).as("a retry is the same return, not a second one").isEqualTo(idOf(first));
		assertThat(onHand()).as("stock must not be withdrawn twice").isEqualByComparingTo("24");
		assertThat(returnCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("a line belonging to another delivery cannot be returned under this receipt")
	void aLineFromAnotherReceiptIsRefused() throws Exception {
		Receipt first = receive("PO-2026-0207", "30");
		Receipt second = receive("PO-2026-0208", "30");

		// Both ids come from the client, so the pairing is checked rather than assumed — otherwise
		// one lorry's goods would be capped against another lorry's quantity.
		mvc.perform(returnGoods(second.receiptId, body("r1", first.lineId, "5", "SPOILED", null)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-400030"));

		assertThat(onHand()).isEqualByComparingTo("60");
		assertThat(returnCount()).isZero();
	}

	// ---------------------------------------------------------------------

	/** Receives {@code qty} of rice on a fresh sent PO, and hands back what a return needs. */
	private Receipt receive(String poNumber, String qty) throws Exception {
		UUID poId = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, sent_at, created_by)
				VALUES (?, ?, ?, 'SENT', now(), ?) RETURNING id
				""", UUID.class, tenant, poNumber, vendor, staffId);
		UUID poLine = admin.queryForObject("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit)
				VALUES (?, ?, ?, ?::numeric, 'KG') RETURNING id
				""", UUID.class, tenant, poId, rice, qty);
		String json = mvc.perform(authed(post("/api/v1/purchase-orders/{poId}/receipts", poId))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"idempotencyKey\":\"" + poNumber + "\",\"lines\":[{\"poLineId\":\"" + poLine
								+ "\",\"receivedQty\":" + qty + ",\"rejectedQty\":0}]}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var tree = JSON.readTree(json);
		return new Receipt(
				poId,
				UUID.fromString(tree.get("id").asText()),
				UUID.fromString(tree.get("lines").get(0).get("id").asText()),
				UUID.fromString(tree.get("lines").get(0).get("batchId").asText()));
	}

	private static String body(String key, UUID lineId, String qty, String reason, String note) {
		String n = note == null ? "" : ",\"note\":\"" + note + "\"";
		return "{\"idempotencyKey\":\"" + key + "\",\"receiptLineId\":\"" + lineId + "\",\"quantity\":"
				+ qty + ",\"reason\":\"" + reason + "\"" + n + "}";
	}

	private MockHttpServletRequestBuilder returnGoods(UUID receiptId, String json) {
		return authed(post("/api/v1/goods-receipts/{receiptId}/returns", receiptId))
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private BigDecimal onHand() {
		return admin.queryForObject(
				"SELECT COALESCE(SUM(quantity), 0) FROM stock_movements WHERE ingredient_id = ?",
				BigDecimal.class, rice);
	}

	private BigDecimal batchStock(UUID batchId) {
		return admin.queryForObject(
				"SELECT COALESCE(SUM(quantity), 0) FROM stock_movements WHERE batch_id = ?",
				BigDecimal.class, batchId);
	}

	private int returnCount() {
		Integer count = admin.queryForObject("SELECT count(*) FROM goods_returns", Integer.class);
		return count == null ? 0 : count;
	}

	private int movementCount(String type) {
		Integer count = admin.queryForObject(
				"SELECT count(*) FROM stock_movements WHERE movement_type = ?", Integer.class, type);
		return count == null ? 0 : count;
	}

	/** The whole receipt line rendered as text, so any change to any column of it is visible. */
	private String receiptLineAsText(UUID lineId) {
		return admin.queryForObject(
				"SELECT l::text FROM goods_receipt_lines l WHERE l.id = ?", String.class, lineId);
	}

	private static String idOf(String json) throws Exception {
		return JSON.readTree(json).get("id").asText();
	}

	private record Receipt(UUID poId, UUID receiptId, UUID lineId, UUID batchId) {
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
