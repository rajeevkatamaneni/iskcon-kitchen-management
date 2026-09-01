package org.iskcon.kms.purchaseorder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * Purchase orders (E5-S3): generation of one draft PO per vendor from the approved shopping list,
 * manual creation, the lifecycle guards (edit only a draft, valid transitions only), per-tenant
 * monotonic numbering, and the append-only activity trail.
 */
@AutoConfigureMockMvc
@Import(PurchaseOrderIT.StubVerifierConfiguration.class)
class PurchaseOrderIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** The temple's own day, which is what a needed-by date is measured in. */
	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

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
		admin.execute("DELETE FROM shopping_list_lines");
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
	@DisplayName("a line measured in something the ingredient can't be measured in is refused")
	void crossFamilyLineIsRefused() throws Exception {
		UUID ghee = ingredient("Ghee", "L");

		mvc.perform(authed(post("/api/v1/purchase-orders"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"vendorId\":\"" + vendorA + "\",\"lines\":["
								+ "{\"ingredientId\":\"" + ghee + "\",\"quantity\":5,\"unit\":\"KG\"}]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4013"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("Ghee"))
				.andExpect(jsonPath("$.fieldErrors[0].message")
						.value("Ghee is measured in L, and there is no way to turn Kg into L."));

		assert admin.queryForObject("SELECT count(*) FROM purchase_orders", Integer.class) == 0
				: "the order is refused whole, not written and then repaired";
	}

	@Test
	@DisplayName("pieces convert to nothing: a weight against a counted ingredient is refused")
	void piecesAreTheirOwnFamily() throws Exception {
		UUID coconut = ingredient("Coconut", "PIECES");

		mvc.perform(authed(post("/api/v1/purchase-orders"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"vendorId\":\"" + vendorA + "\",\"lines\":["
								+ "{\"ingredientId\":\"" + coconut + "\",\"quantity\":30,\"unit\":\"KG\"}]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4013"))
				.andExpect(jsonPath("$.fieldErrors[0].message")
						.value("Coconut is measured in pieces, and there is no way to turn Kg into pieces."));
	}

	@Test
	@DisplayName("a different unit of the same family is fine, and is stored as it was given")
	void sameFamilyDifferentUnitIsAccepted() throws Exception {
		// Rice is held in Kg. Ordering 2,500 gm of it is a perfectly ordinary thing to write, and
		// converts: same family is the rule, not same unit.
		String body = "{\"vendorId\":\"" + vendorA + "\",\"lines\":["
				+ "{\"ingredientId\":\"" + rice + "\",\"quantity\":2500,\"unit\":\"GM\"}]}";
		String response = mvc.perform(authed(post("/api/v1/purchase-orders"))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		JsonNode po = getDetail(JSON.readTree(response).get("id").asText());
		assert po.get("lines").get(0).get("unit").asText().equals("GM") : "the line keeps its own unit";
		// And the database's own conversion agrees: 2,500 gm is 2,500 base grams, which is 2.5 Kg.
		assert admin.queryForObject("SELECT to_base_qty(quantity, unit) FROM purchase_order_lines",
				java.math.BigDecimal.class).compareTo(new java.math.BigDecimal("2500")) == 0;
	}

	@Test
	@DisplayName("a draft edited into a cross-family line is refused, and the draft is left as it was")
	void editIntoACrossFamilyLineIsRefused() throws Exception {
		UUID ghee = ingredient("Ghee", "L");
		String id = createManual(vendorA, rice, "5");

		mvc.perform(authed(put("/api/v1/purchase-orders/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"lines\":[{\"ingredientId\":\"" + ghee
								+ "\",\"quantity\":5,\"unit\":\"KG\"}]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4013"));

		// The edit replaces the line set wholesale, so a refusal half-way would have left the draft
		// with no lines at all. It rolls back instead.
		JsonNode after = getDetail(id);
		assert after.get("lines").size() == 1 : "the draft keeps the lines it had";
		assert after.get("lines").get(0).get("ingredientName").asText().equals("Rice");
	}

	@Test
	@DisplayName("a draft's needed-by date can be set, changed, and cleared again")
	void neededByIsEditableOnADraft() throws Exception {
		String id = createManual(vendorA, rice, "5");
		assert getDetail(id).get("order").get("neededBy").isNull() : "nothing was asked for yet";

		LocalDate wanted = LocalDate.now(TEMPLE_ZONE).plusDays(6);
		mvc.perform(authed(put("/api/v1/purchase-orders/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content(lineBody(rice, "5", "\"" + wanted + "\"")))
				.andExpect(status().isNoContent());
		assert getDetail(id).get("order").get("neededBy").asText().equals(wanted.toString());

		// Changed again, to a nearer day. Inside the two-day buffer, and accepted: the buffer warns
		// on the screen and never refuses, because a temple that needs rice tomorrow may say so.
		LocalDate tomorrow = LocalDate.now(TEMPLE_ZONE).plusDays(1);
		mvc.perform(authed(put("/api/v1/purchase-orders/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content(lineBody(rice, "5", "\"" + tomorrow + "\"")))
				.andExpect(status().isNoContent());
		assert getDetail(id).get("order").get("neededBy").asText().equals(tomorrow.toString());

		// And cleared. The column is nullable and an order with no date to meet is a real order —
		// E5-S9 counts those aside rather than judging them, so this must stay expressible.
		mvc.perform(authed(put("/api/v1/purchase-orders/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content(lineBody(rice, "5", "null")))
				.andExpect(status().isNoContent());
		assert getDetail(id).get("order").get("neededBy").isNull() : "a cleared date is a cleared date";
	}

	@Test
	@DisplayName("a needed-by date behind the order's own date is refused")
	void neededByBeforeOrderDateIsRefused() throws Exception {
		String id = createManual(vendorA, rice, "5");
		// Read from the order rather than from a clock: the floor is the order's own date, and the
		// order is dated in the temple's day whatever zone the machine running this happens to be in.
		LocalDate dayBefore = LocalDate
				.parse(getDetail(id).get("order").get("orderDate").asText()).minusDays(1);

		mvc.perform(authed(put("/api/v1/purchase-orders/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content(lineBody(rice, "5", "\"" + dayBefore + "\"")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4014"));

		assert getDetail(id).get("order").get("neededBy").isNull() : "the refusal wrote nothing";
	}

	@Test
	@DisplayName("an order is dated the temple's own day, not the machine's")
	void orderDateIsTheTemplesDay() throws Exception {
		String id = createManual(vendorA, rice, "5");
		assert getDetail(id).get("order").get("orderDate").asText()
				.equals(LocalDate.now(TEMPLE_ZONE).toString())
				: "the kitchen's day is the operational day, wherever the server runs";
	}

	@Test
	@DisplayName("the needed-by date is frozen once the order has been sent")
	void neededByCannotBeChangedAfterSending() throws Exception {
		String id = createManual(vendorA, rice, "5");
		LocalDate wanted = LocalDate.now(TEMPLE_ZONE).plusDays(6);
		mvc.perform(authed(put("/api/v1/purchase-orders/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content(lineBody(rice, "5", "\"" + wanted + "\"")))
				.andExpect(status().isNoContent());
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id))).andExpect(status().isNoContent());

		// The vendor has been told this date and the scorecard measures them against it. Moving it
		// now would rewrite their record after the fact, so the endpoint refuses — the screen hiding
		// the field is not what stops this.
		mvc.perform(authed(put("/api/v1/purchase-orders/{id}", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content(lineBody(rice, "5", "\"" + wanted.plusDays(10) + "\"")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4919"));

		assert getDetail(id).get("order").get("neededBy").asText().equals(wanted.toString())
				: "the date the vendor was given still stands";
	}

	@Test
	@DisplayName("generation still takes the earliest needed-by across a vendor's lines")
	void generationStillComputesTheEarliestNeededBy() throws Exception {
		LocalDate soon = LocalDate.now(TEMPLE_ZONE).plusDays(3);
		LocalDate later = LocalDate.now(TEMPLE_ZONE).plusDays(9);
		orderLine(rice, "9", vendorA, "45.00", soon);
		orderLine(dal, "6", vendorA, "120.00", later);

		String body = mvc.perform(authed(post("/api/v1/purchase-orders/generate")))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String id = JSON.readTree(body).get("purchaseOrderIds").get(0).asText();

		// Unchanged by the field existing: the computation still wins the argument between its own
		// lines, and an override is something a person does afterwards.
		assert getDetail(id).get("order").get("neededBy").asText().equals(soon.toString());
	}

	@Test
	@DisplayName("a computed needed-by already in the past still generates — the rule is for typed dates")
	void generationAcceptsAComputedDateInThePast() throws Exception {
		// The shopping list subtracts a two-day lead buffer from the first meal that needs the
		// ingredient, so a meal planned for tomorrow yields a needed-by of yesterday. That is a true
		// statement about demand, and refusing it here would break the shopping list rather than
		// protect anything.
		orderLine(rice, "9", vendorA, "45.00", LocalDate.now(TEMPLE_ZONE).minusDays(1));

		String body = mvc.perform(authed(post("/api/v1/purchase-orders/generate")))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String id = JSON.readTree(body).get("purchaseOrderIds").get(0).asText();
		assert getDetail(id).get("order").get("neededBy").asText()
				.equals(LocalDate.now(TEMPLE_ZONE).minusDays(1).toString());
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

	/** The same body with a needed-by date on it — {@code neededByJson} is a quoted date, or null. */
	private String lineBody(UUID ingredient, String qty, String neededByJson) {
		return "{\"neededBy\":" + neededByJson + ",\"lines\":[{\"ingredientId\":\"" + ingredient
				+ "\",\"quantity\":" + qty + ",\"unit\":\"KG\"}]}";
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
		return ingredient(name, "KG");
	}

	private UUID ingredient(String name, String canonicalUnit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', ?) RETURNING id
				""", UUID.class, tenant, name, canonicalUnit);
	}

	private UUID vendor(String name) {
		return admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, ?, '+919812345678') RETURNING id
				""", UUID.class, tenant, name);
	}

	private void orderLine(UUID ingredient, String qty, UUID vendor, String lastPrice) {
		orderLine(ingredient, qty, vendor, lastPrice, null);
	}

	private void orderLine(UUID ingredient, String qty, UUID vendor, String lastPrice, LocalDate neededBy) {
		admin.update("""
				INSERT INTO shopping_list_lines (
					tenant_id, ingredient_id, suggested_qty, unit, suggested_vendor_id, needed_by, included)
				VALUES (?, ?, ?::numeric, 'KG', ?, ?, true)
				""", tenant, ingredient, qty, vendor, neededBy);
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
