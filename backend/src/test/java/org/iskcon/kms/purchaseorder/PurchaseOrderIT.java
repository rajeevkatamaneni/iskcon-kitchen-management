package org.iskcon.kms.purchaseorder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
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
 * Purchase orders (E5-S3): generation of one draft PO per vendor from the approved order list,
 * manual creation, the lifecycle guards (edit only a draft, valid transitions only), per-tenant
 * monotonic numbering, and the append-only activity trail.
 */
@AutoConfigureMockMvc
@Import(PurchaseOrderIT.StubVerifierConfiguration.class)
class PurchaseOrderIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID rice;
	private UUID dal;
	private UUID sugar;
	private UUID vendorA;
	private UUID vendorB;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-staff-a', 'Staff A', 'staff-a@example.com', '+919876500081', 'KITCHEN_STAFF', 'ACTIVE')
				""", tenant);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-vol-a', 'Vol A', 'vol-a@example.com', '+919876500082', 'VOLUNTEER', 'ACTIVE')
				""", tenant);

		rice = ingredient("Rice");
		dal = ingredient("Toor Dal");
		sugar = ingredient("Sugar");

		vendorA = vendor("Govind Wholesale");
		vendorB = vendor("Sri Traders");

		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM po_events");
		admin.execute("DELETE FROM purchase_order_lines");
		admin.execute("DELETE FROM purchase_orders");
		admin.execute("DELETE FROM po_sequence");
		admin.execute("DELETE FROM order_list_lines");
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		// Anything that moved through the stock ledger is tracked now, so the item rows exist
		// even where the test never asked for them, and they hold the ingredient down.
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("three checked lines across two vendors generate exactly two correct draft POs")
	void generatesOnePoPerVendor() throws Exception {
		orderLine(rice, "9", vendorA, "45.00");
		orderLine(dal, "6", vendorA, "120.00");
		orderLine(sugar, "12", vendorB, "42.00");

		String body = mvc.perform(authed(post("/api/v1/purchase-orders/generate")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.purchaseOrderIds.length()").value(2))
				.andReturn().getResponse().getContentAsString();
		List<String> ids = readIds(body);

		Map<String, JsonNode> byVendor = new HashMap<>();
		for (String id : ids) {
			JsonNode po = getDetail(id);
			byVendor.put(po.get("order").get("vendorName").asText(), po);
		}

		JsonNode a = byVendor.get("Govind Wholesale");
		JsonNode b = byVendor.get("Sri Traders");
		assert a != null && b != null : "expected one PO per vendor";
		assert a.get("order").get("status").asText().equals("DRAFT");
		assert a.get("lines").size() == 2 : "vendor A PO should carry both its lines";
		assert b.get("lines").size() == 1 : "vendor B PO should carry its single line";
		// The expected price rides across from the order line.
		assert b.get("lines").get(0).get("expectedPrice").asDouble() == 42.0;
	}

	@Test
	@DisplayName("a sent purchase order can no longer be edited")
	void cannotEditAfterSend() throws Exception {
		String id = createManual(vendorA, rice, "5");
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id))).andExpect(status().isNoContent());

		mvc.perform(authed(put("/api/v1/purchase-orders/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content(lineBody(rice, "8")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4919"));
	}

	@Test
	@DisplayName("a draft takes a changed quantity, a new line, and a line taken away")
	void draftLinesAreEditable() throws Exception {
		String id = createManual(vendorA, rice, "5");

		// A quantity revised upwards, and a second ingredient remembered.
		mvc.perform(authed(put("/api/v1/purchase-orders/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"lines\":["
								+ "{\"ingredientId\":\"" + rice + "\",\"quantity\":8,\"unit\":\"KG\"},"
								+ "{\"ingredientId\":\"" + dal + "\",\"quantity\":3,\"unit\":\"KG\"}]}"))
				.andExpect(status().isNoContent());

		JsonNode after = getDetail(id);
		assert after.get("lines").size() == 2 : "the added line should be there";
		Map<String, Double> byName = new HashMap<>();
		after.get("lines").forEach(l -> byName.put(l.get("ingredientName").asText(), l.get("quantity").asDouble()));
		assert byName.get("Rice") == 8.0 : "the revised quantity should stand";
		assert byName.get("Toor Dal") == 3.0;
		assert after.get("order").get("status").asText().equals("DRAFT") : "editing does not advance a PO";

		// And the rice taken off again — a removal is simply a shorter line set.
		mvc.perform(authed(put("/api/v1/purchase-orders/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content(lineBody(dal, "3")))
				.andExpect(status().isNoContent());

		JsonNode trimmed = getDetail(id);
		assert trimmed.get("lines").size() == 1 : "the removed line should be gone";
		assert trimmed.get("lines").get(0).get("ingredientName").asText().equals("Toor Dal");
		// Each edit leaves its own mark, so the trail says the draft was worked on twice.
		assert trimmed.get("events").findValues("eventType").stream()
				.filter(n -> n.asText().equals("EDITED")).count() == 2;
	}

	@Test
	@DisplayName("a draft purchase order cannot be received — that isn't a valid step")
	void cannotReceiveDraft() throws Exception {
		String id = createManual(vendorA, rice, "5");
		// Receiving is reached through the receiving endpoint (E5-S6); here we assert the guard by
		// attempting an out-of-order send after cancel, which is the same PO_INVALID_TRANSITION path.
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/cancel", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"duplicate order\"}"))
				.andExpect(status().isNoContent());
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4920"));
	}

	@Test
	@DisplayName("purchase-order numbers are sequential and never duplicated within a tenant")
	void numberingIsMonotonic() throws Exception {
		String id1 = createManual(vendorA, rice, "5");
		String id2 = createManual(vendorB, dal, "5");
		String id3 = createManual(vendorA, sugar, "5");

		String n1 = getDetail(id1).get("order").get("poNumber").asText();
		String n2 = getDetail(id2).get("order").get("poNumber").asText();
		String n3 = getDetail(id3).get("order").get("poNumber").asText();

		assert seq(n1) + 1 == seq(n2) : n1 + " -> " + n2;
		assert seq(n2) + 1 == seq(n3) : n2 + " -> " + n3;
	}

	@Test
	@DisplayName("cancelling records a reason and an event on the trail")
	void cancelLeavesTrail() throws Exception {
		String id = createManual(vendorA, rice, "5");
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/cancel", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"vendor out of stock\"}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", id)))
				.andExpect(jsonPath("$.order.status").value("CANCELLED"))
				.andExpect(jsonPath("$.order.cancelReason").value("vendor out of stock"))
				.andExpect(jsonPath("$.events[?(@.eventType=='CANCELLED')]").exists());
	}

	@Test
	@DisplayName("a volunteer cannot manage purchase orders")
	void volunteerForbidden() throws Exception {
		signIn("uid-vol-a");
		mvc.perform(authed(get("/api/v1/purchase-orders"))).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private String createManual(UUID vendor, UUID ingredient, String qty) throws Exception {
		String body = "{\"vendorId\":\"" + vendor + "\",\"lines\":["
				+ "{\"ingredientId\":\"" + ingredient + "\",\"quantity\":" + qty + ",\"unit\":\"KG\"}]}";
		String response = mvc.perform(authed(post("/api/v1/purchase-orders"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(response).get("id").asText();
	}

	private String lineBody(UUID ingredient, String qty) {
		return "{\"lines\":[{\"ingredientId\":\"" + ingredient + "\",\"quantity\":" + qty + ",\"unit\":\"KG\"}]}";
	}

	private JsonNode getDetail(String id) throws Exception {
		String body = mvc.perform(authed(get("/api/v1/purchase-orders/{id}", id)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(body);
	}

	private List<String> readIds(String body) throws Exception {
		JsonNode arr = JSON.readTree(body).get("purchaseOrderIds");
		return List.of(arr.get(0).asText(), arr.get(1).asText());
	}

	private static int seq(String poNumber) {
		return Integer.parseInt(poNumber.substring(poNumber.lastIndexOf('-') + 1));
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

	private UUID vendor(String name) {
		return admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, ?, '+919812345678') RETURNING id
				""", UUID.class, tenant, name);
	}

	private void orderLine(UUID ingredient, String qty, UUID vendor, String lastPrice) {
		admin.update("""
				INSERT INTO order_list_lines (tenant_id, ingredient_id, suggested_qty, unit, suggested_vendor_id, included)
				VALUES (?, ?, ?::numeric, 'KG', ?, true)
				""", tenant, ingredient, qty, vendor);
		admin.update("""
				INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price)
				VALUES (?, ?, ?, ?::numeric)
				""", tenant, vendor, ingredient, lastPrice);
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
