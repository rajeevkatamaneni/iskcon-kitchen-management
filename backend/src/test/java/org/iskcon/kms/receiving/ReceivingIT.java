package org.iskcon.kms.receiving;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
 * Receiving deliveries against a purchase order (E5-S6): received goods write PO_RECEIPT movements
 * with a batch, rejected goods are recorded but never touch stock, the PO status auto-derives, the
 * outstanding quantity re-feeds the order list, and a duplicate submission cannot double-book.
 */
@AutoConfigureMockMvc
@Import(ReceivingIT.StubVerifierConfiguration.class)
class ReceivingIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

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
		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM goods_receipt_lines");
		admin.execute("DELETE FROM goods_receipts");
		admin.execute("DELETE FROM order_list_lines");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM po_events");
		admin.execute("DELETE FROM purchase_order_lines");
		admin.execute("DELETE FROM purchase_orders");
		admin.execute("DELETE FROM po_sequence");
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("30 received / 2 rejected of 36 → stock +30 with a batch, rejection recorded, PO partially received, 6 re-fed")
	void partialReceiptBooksGoodRejectsBadAndRefeeds() throws Exception {
		UUID poId = sentPo("PO-2026-0042");
		UUID line = poLine(poId, rice, "36");

		mvc.perform(receive(poId, "{\"idempotencyKey\":\"k1\",\"lines\":["
						+ "{\"poLineId\":\"" + line + "\",\"receivedQty\":30,\"rejectedQty\":2,\"rejectReason\":\"SPOILED\"}]}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.lines[0].receivedQty").value(30))
				.andExpect(jsonPath("$.lines[0].rejectedQty").value(2))
				.andExpect(jsonPath("$.lines[0].rejectReason").value("SPOILED"))
				.andExpect(jsonPath("$.lines[0].batchId").exists());

		// Stock reflects the truck: +30 only, in exactly one PO_RECEIPT movement. The rejected 2 never book.
		assert onHand(rice).compareTo(new BigDecimal("30.000")) == 0 : "stock should be +30, was " + onHand(rice);
		Integer receipts = admin.queryForObject(
				"SELECT count(*) FROM stock_movements WHERE ingredient_id = ? AND movement_type = 'PO_RECEIPT'",
				Integer.class, rice);
		assert receipts == 1 : "exactly one receipt movement expected";

		// PO is now partially received.
		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", poId)))
				.andExpect(jsonPath("$.order.status").value("PARTIALLY_RECEIVED"));

		// The 6 still outstanding re-feed the next generated order list, traceable to the PO.
		mvc.perform(authed(post("/api/v1/order-list/regenerate"))).andExpect(status().isOk());
		mvc.perform(authed(get("/api/v1/order-list")))
				.andExpect(jsonPath("$[?(@.ingredientName=='Rice')]").exists())
				.andExpect(jsonPath("$[0].suggestedQty").value(6))
				.andExpect(jsonPath("$[0].poOutstanding").value(6))
				.andExpect(jsonPath("$[0].shortPurchaseOrders[0]").value("PO-2026-0042"));
	}

	@Test
	@DisplayName("a second receipt that covers the balance flips the PO to received")
	void secondReceiptCompletesPo() throws Exception {
		UUID poId = sentPo("PO-2026-0043");
		UUID line = poLine(poId, rice, "36");

		mvc.perform(receive(poId, body(line, "k1", 30, 0, null))).andExpect(status().isCreated());
		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", poId)))
				.andExpect(jsonPath("$.order.status").value("PARTIALLY_RECEIVED"));

		mvc.perform(receive(poId, body(line, "k2", 6, 0, null))).andExpect(status().isCreated());
		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", poId)))
				.andExpect(jsonPath("$.order.status").value("RECEIVED"));
		assert onHand(rice).compareTo(new BigDecimal("36.000")) == 0;
	}

	@Test
	@DisplayName("a duplicate submission with the same key cannot double-book stock")
	void duplicateSubmissionIsIdempotent() throws Exception {
		UUID poId = sentPo("PO-2026-0044");
		UUID line = poLine(poId, rice, "36");

		String first = mvc.perform(receive(poId, body(line, "same-key", 30, 0, null)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String again = mvc.perform(receive(poId, body(line, "same-key", 30, 0, null)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		// Same receipt returned, and stock booked once.
		assert idOf(first).equals(idOf(again)) : "duplicate should return the same receipt";
		assert onHand(rice).compareTo(new BigDecimal("30.000")) == 0 : "stock must not double-book";
		Integer count = admin.queryForObject("SELECT count(*) FROM goods_receipts WHERE po_id = ?", Integer.class, poId);
		assert count == 1 : "only one receipt should exist";
	}

	@Test
	@DisplayName("a draft purchase order cannot be received")
	void cannotReceiveDraft() throws Exception {
		UUID poId = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, created_by)
				VALUES (?, 'PO-2026-0099', ?, 'DRAFT', ?) RETURNING id
				""", UUID.class, tenant, vendor, staffId);
		UUID line = poLine(poId, rice, "10");
		mvc.perform(receive(poId, body(line, "k1", 10, 0, null)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4920"));
	}

	// ---------------------------------------------------------------------

	private UUID sentPo(String number) {
		return admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, sent_at, created_by)
				VALUES (?, ?, ?, 'SENT', now(), ?) RETURNING id
				""", UUID.class, tenant, number, vendor, staffId);
	}

	private UUID poLine(UUID poId, UUID ingredient, String qty) {
		return admin.queryForObject("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit)
				VALUES (?, ?, ?, ?::numeric, 'KG') RETURNING id
				""", UUID.class, tenant, poId, ingredient, qty);
	}

	private BigDecimal onHand(UUID ingredient) {
		return admin.queryForObject(
				"SELECT COALESCE(SUM(quantity), 0) FROM stock_movements WHERE ingredient_id = ?",
				BigDecimal.class, ingredient);
	}

	private static String body(UUID line, String key, int received, int rejected, String reason) {
		String r = reason == null ? "" : ",\"rejectReason\":\"" + reason + "\"";
		return "{\"idempotencyKey\":\"" + key + "\",\"lines\":[{\"poLineId\":\"" + line
				+ "\",\"receivedQty\":" + received + ",\"rejectedQty\":" + rejected + r + "}]}";
	}

	private static String idOf(String json) throws Exception {
		return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get("id").asText();
	}

	private MockHttpServletRequestBuilder receive(UUID poId, String json) {
		return authed(post("/api/v1/purchase-orders/{poId}/receipts", poId))
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
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
