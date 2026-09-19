package org.iskcon.kms.receiving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
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
 * The Deliveries screen's server (PROCUREMENT-REQUIREMENTS §7, R-DEL-1..5; T-261): what every
 * vendor still owes, what came, and recording one vendor's van across their open orders through
 * the one recording path, {@code ReceivingService.receive}.
 *
 * <p>Four people sign in, each on a token of their own so a test can switch between them without
 * re-registering: a Kitchen Manager, a Temple Admin and a Kitchen Staff member, who all hold
 * {@code RECEIVE_DELIVERIES}, and a Volunteer, who does not. Kitchen Staff was open question Q-1
 * ("Does Kitchen Staff get RECEIVE_DELIVERIES by default, or only named staff?") until Rajeev
 * answered it on 2026-09-19: by default (T-282).
 */
@AutoConfigureMockMvc
class DeliveriesIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** The temple's zone, set on the tenant below. The server reads it; the test states it. */
	private static final ZoneId TEMPLE = ZoneId.of("Asia/Kolkata");

	private static final String MANAGER = "manager-token";
	private static final String ADMIN = "admin-token";
	private static final String STAFF = "staff-token";
	private static final String VOLUNTEER = "volunteer-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID managerId;
	private UUID rice;
	private UUID dal;
	private UUID vendorA;
	private UUID vendorB;
	private int poSeq;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		managerId = user("uid-manager", "Karuna Murti Das", "manager@example.com", "+919876500091", "KITCHEN_MANAGER");
		user("uid-admin", "Madhava Das", "admin@example.com", "+919876500092", "TEMPLE_ADMIN");
		user("uid-staff", "Staff A", "staff@example.com", "+919876500093", "KITCHEN_STAFF");
		user("uid-volunteer", "Volunteer A", "volunteer@example.com", "+919876500094", "VOLUNTEER");
		stubVerifier.accept(MANAGER, new TokenVerifier.VerifiedSubject("uid-manager", "manager@example.com", "+919876500091"));
		stubVerifier.accept(ADMIN, new TokenVerifier.VerifiedSubject("uid-admin", "admin@example.com", "+919876500092"));
		stubVerifier.accept(STAFF, new TokenVerifier.VerifiedSubject("uid-staff", "staff@example.com", "+919876500093"));
		stubVerifier.accept(VOLUNTEER, new TokenVerifier.VerifiedSubject("uid-volunteer", "volunteer@example.com", "+919876500094"));

		rice = ingredient("Rice");
		dal = ingredient("Toor dal");
		vendorA = vendor("Balaji Traders", "+919812345601");
		vendorB = vendor("Heritage Dairy", "+919812345602");
		poSeq = 0;
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM goods_returns");
		admin.execute("DELETE FROM goods_receipt_lines");
		admin.execute("DELETE FROM goods_receipts");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM po_events");
		admin.execute("DELETE FROM purchase_order_lines");
		admin.execute("DELETE FROM purchase_orders");
		admin.execute("DELETE FROM po_sequence");
		admin.execute("DELETE FROM vendor_price_history");
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredient_pack_sizes");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- AC-DEL: partial, then the rest ----------------------------------------

	@Test
	@DisplayName("a partial delivery then the rest: the line completes on the second date, and its parts show both, in order, with who received them")
	void partialThenTheRest() throws Exception {
		UUID po = sentPo(vendorA, LocalDate.now(TEMPLE));
		UUID line = poLine(po, rice, "50");
		LocalDate today = LocalDate.now(TEMPLE);

		// 32 Kg came off the van: 30 kept, 2 refused as spoiled.
		JsonNode first = postJson(MANAGER, deliveryBody(vendorA, "van-1", line, "30", "2", "SPOILED"));
		assertThat(first.get("receiptIds")).hasSize(1);
		// The first van came three days ago. Moved back by the schema owner, because the table is
		// append-only to the application and nothing else can.
		UUID firstReceipt = UUID.fromString(first.get("receiptIds").get(0).asText());
		admin.update("UPDATE goods_receipts SET received_at = received_at - interval '3 days' WHERE id = ?",
				firstReceipt);

		JsonNode midway = getJson(MANAGER, "/api/v1/deliveries");
		JsonNode open = only(midway.get("open"));
		// Rejected goods stay owed: 50 − 30 = 20, not 18.
		assertThat(open.get("receivedQty").decimalValue()).isEqualByComparingTo("30");
		assertThat(open.get("rejectedQty").decimalValue()).isEqualByComparingTo("2");
		assertThat(open.get("stillToCome").decimalValue()).isEqualByComparingTo("20");
		assertThat(open.get("completedOn").isNull()).isTrue();
		assertThat(open.get("parts")).hasSize(1);

		// The rest, recorded the same way by somebody else (R-DEL-3: "Nothing new to learn").
		postJson(ADMIN, deliveryBody(vendorA, "van-2", line, "20", "0", null));

		JsonNode after = getJson(MANAGER, "/api/v1/deliveries");
		assertThat(after.get("open")).isEmpty();
		JsonNode done = lineIn(after.get("receivedLines"), line);
		assertThat(done.get("stillToCome").decimalValue()).isEqualByComparingTo("0");
		assertThat(done.get("receivedQty").decimalValue()).isEqualByComparingTo("50");
		assertThat(done.get("completedOn").asText()).isEqualTo(today.toString());
		assertThat(done.get("orderedQty").decimalValue()).isEqualByComparingTo("50");

		JsonNode parts = done.get("parts");
		assertThat(parts).hasSize(2);
		assertThat(parts.get(0).get("receivedOn").asText()).isEqualTo(today.minusDays(3).toString());
		assertThat(parts.get(0).get("receivedQty").decimalValue()).isEqualByComparingTo("30");
		assertThat(parts.get(0).get("rejectedQty").decimalValue()).isEqualByComparingTo("2");
		assertThat(parts.get(0).get("rejectReason").asText()).isEqualTo("SPOILED");
		assertThat(parts.get(0).get("receivedByName").asText()).isEqualTo("Karuna Murti Das");
		assertThat(parts.get(1).get("receivedOn").asText()).isEqualTo(today.toString());
		assertThat(parts.get(1).get("receivedQty").decimalValue()).isEqualByComparingTo("20");
		assertThat(parts.get(1).get("receivedByName").asText()).isEqualTo("Madhava Das");
		// R-DEL-4: no order number in a part.
		assertThat(parts.get(0).has("poNumber")).isFalse();

		// The one recording path did what it always does: stock, and the order's status.
		assertThat(onHand(rice)).isEqualByComparingTo("50");
		assertThat(admin.queryForObject("SELECT status FROM purchase_orders WHERE id = ?", String.class, po))
				.isEqualTo("RECEIVED");
		// Newest first, both receipts in the window.
		assertThat(after.get("received")).hasSize(2);
		assertThat(after.get("received").get(0).get("receivedByName").asText()).isEqualTo("Madhava Das");
		assertThat(after.get("hasOlder").asBoolean()).isFalse();
	}

	@Test
	@DisplayName("a rejection stays owed: everything refused leaves the whole line still to come")
	void aRejectionStaysOwed() throws Exception {
		UUID po = sentPo(vendorA, null);
		UUID line = poLine(po, dal, "10");

		postJson(MANAGER, deliveryBody(vendorA, "van-1", line, "0", "10", "DAMAGED"));

		JsonNode open = only(getJson(MANAGER, "/api/v1/deliveries").get("open"));
		assertThat(open.get("poLineId").asText()).isEqualTo(line.toString());
		assertThat(open.get("stillToCome").decimalValue()).isEqualByComparingTo("10");
		assertThat(open.get("rejectedQty").decimalValue()).isEqualByComparingTo("10");
		assertThat(onHand(dal)).isEqualByComparingTo("0");
		assertThat(admin.queryForObject("SELECT status FROM purchase_orders WHERE id = ?", String.class, po))
				.isEqualTo("PARTIALLY_RECEIVED");
	}

	// ---- One van, and only this vendor's open orders ---------------------------

	@Test
	@DisplayName("one van across two of a vendor's orders makes one receipt per order")
	void oneVanAcrossTwoOrders() throws Exception {
		UUID po1 = sentPo(vendorA, null);
		UUID po2 = sentPo(vendorA, null);
		UUID l1 = poLine(po1, rice, "10");
		UUID l2 = poLine(po2, dal, "5");

		JsonNode recorded = postJson(MANAGER, "{\"vendorId\":\"" + vendorA + "\",\"idempotencyKey\":\"van-1\",\"lines\":["
				+ lineJson(l1, "10", "0", null) + "," + lineJson(l2, "5", "0", null) + "]}");

		assertThat(recorded.get("receiptIds")).hasSize(2);
		assertThat(admin.queryForObject("SELECT count(*) FROM goods_receipts WHERE po_id = ?", Integer.class, po1)).isEqualTo(1);
		assertThat(admin.queryForObject("SELECT count(*) FROM goods_receipts WHERE po_id = ?", Integer.class, po2)).isEqualTo(1);
		assertThat(onHand(rice)).isEqualByComparingTo("10");
		assertThat(onHand(dal)).isEqualByComparingTo("5");
	}

	@Test
	@DisplayName("a line from another vendor's order refuses the whole van with KMS-400164, and nothing is written")
	void anotherVendorsLineRefusesTheWholeVan() throws Exception {
		UUID mine = poLine(sentPo(vendorA, null), rice, "10");
		UUID theirs = poLine(sentPo(vendorB, null), dal, "5");

		mvc.perform(postDelivery(MANAGER, "{\"vendorId\":\"" + vendorA + "\",\"idempotencyKey\":\"van-1\",\"lines\":["
						+ lineJson(mine, "10", "0", null) + "," + lineJson(theirs, "5", "0", null) + "]}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400164"));

		assertNothingWritten();
	}

	@Test
	@DisplayName("a line on this vendor's order that is not sent or part-delivered is refused with KMS-400164 too")
	void aLineOnADraftIsRefused() throws Exception {
		UUID draft = insertPo(vendorA, "DRAFT", null);
		UUID line = poLine(draft, rice, "10");

		mvc.perform(postDelivery(MANAGER, deliveryBody(vendorA, "van-1", line, "10", "0", null)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400164"));

		assertNothingWritten();
	}

	@Test
	@DisplayName("a retry with the same key records nothing twice, even after the first press completed the orders")
	void aRetryRecordsNothingTwice() throws Exception {
		UUID po1 = sentPo(vendorA, null);
		UUID po2 = sentPo(vendorA, null);
		UUID l1 = poLine(po1, rice, "10");
		UUID l2 = poLine(po2, dal, "5");
		String body = "{\"vendorId\":\"" + vendorA + "\",\"idempotencyKey\":\"van-1\",\"lines\":["
				+ lineJson(l1, "10", "0", null) + "," + lineJson(l2, "5", "0", null) + "]}";

		JsonNode first = postJson(MANAGER, body);
		// Both orders are RECEIVED now, so a retry that re-checked them would be refused. It must
		// answer with what the first press recorded instead.
		JsonNode again = postJson(MANAGER, body);

		assertThat(again.get("receiptIds")).isEqualTo(first.get("receiptIds"));
		assertThat(admin.queryForObject("SELECT count(*) FROM goods_receipts", Integer.class)).isEqualTo(2);
		assertThat(admin.queryForObject("SELECT count(*) FROM goods_receipt_lines", Integer.class)).isEqualTo(2);
		assertThat(admin.queryForObject("SELECT count(*) FROM stock_movements", Integer.class)).isEqualTo(2);
		assertThat(onHand(rice)).isEqualByComparingTo("10");
	}

	// ---- Who may ----------------------------------------------------------------

	/**
	 * Kitchen Staff hold {@code RECEIVE_DELIVERIES}. That was <strong>open question Q-1</strong>
	 * ("Does Kitchen Staff get RECEIVE_DELIVERIES by default, or only named staff?"), and until
	 * Rajeev answered it this test asserted their 403 on every route below. He answered on
	 * 2026-09-19: Kitchen Staff get it by default, the whole role and not named people (T-282). So
	 * whoever is at the gate when the van arrives can open the screen and record what came, on
	 * both recording routes, and the record says it was them.
	 *
	 * <p>Proved through the real routes rather than read off {@code RolePermissions}: a grant that
	 * the table holds but an endpoint's {@code @PreAuthorize} names differently would pass the unit
	 * test and still refuse the cook at the gate.
	 */
	@Test
	@DisplayName("Kitchen Staff (Q-1, answered 2026-09-19) read /deliveries and record a delivery on both routes")
	void kitchenStaffReceiveDeliveries() throws Exception {
		UUID po = sentPo(vendorA, null);
		UUID line = poLine(po, rice, "10");
		UUID po2 = sentPo(vendorB, null);
		UUID line2 = poLine(po2, dal, "5");

		// The screen, and its older deliveries.
		JsonNode screen = getJson(STAFF, "/api/v1/deliveries");
		assertThat(screen.get("open")).hasSize(2);
		getJson(STAFF, "/api/v1/deliveries/received?before=2026-09-01");

		// Recording one vendor's van on the Deliveries route.
		JsonNode van = postJson(STAFF, deliveryBody(vendorA, "van-1", line, "10", "0", null));
		assertThat(van.get("receiptIds")).hasSize(1);

		// And on the order page's route, the other door into the same recording path.
		String receipt = mvc.perform(as(STAFF, post("/api/v1/purchase-orders/{poId}/receipts", po2))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"idempotencyKey\":\"k1\",\"lines\":[" + lineJson(line2, "5", "0", null) + "]}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		assertThat(JSON.readTree(receipt).get("receivedByName").asText()).isEqualTo("Staff A");

		// Both went through: stock moved, both orders are received, and the screen names the cook.
		assertThat(onHand(rice)).isEqualByComparingTo("10");
		assertThat(onHand(dal)).isEqualByComparingTo("5");
		assertThat(admin.queryForObject("SELECT count(*) FROM goods_receipts", Integer.class)).isEqualTo(2);
		for (UUID order : List.of(po, po2)) {
			assertThat(admin.queryForObject("SELECT status FROM purchase_orders WHERE id = ?", String.class, order))
					.isEqualTo("RECEIVED");
		}
		JsonNode after = getJson(STAFF, "/api/v1/deliveries");
		assertThat(after.get("open")).isEmpty();
		assertThat(after.get("received")).hasSize(2);
		after.get("received").forEach(r -> assertThat(r.get("receivedByName").asText()).isEqualTo("Staff A"));
	}

	/**
	 * A Volunteer still does not hold {@code RECEIVE_DELIVERIES}: Rajeev's answer to Q-1 widened it
	 * to Kitchen Staff and no further. Asserted over HTTP so the refusal that remains is proved the
	 * same way the grant is. (The operator's refusal is a table row in {@code RolePermissionsTest}.)
	 *
	 * <p>Reading an order's receipts stays {@code MANAGE_PURCHASE_ORDERS}, which a Volunteer does not
	 * hold either, so they are refused that too.
	 */
	@Test
	@DisplayName("a Volunteer gets 403 on GET and POST /deliveries, older deliveries, and GET and POST receipts")
	void aVolunteerIsRefused() throws Exception {
		UUID po = sentPo(vendorA, null);
		UUID line = poLine(po, rice, "10");

		mvc.perform(as(VOLUNTEER, get("/api/v1/deliveries"))).andExpect(status().isForbidden());
		mvc.perform(as(VOLUNTEER, get("/api/v1/deliveries/received").param("before", "2026-09-01")))
				.andExpect(status().isForbidden());
		mvc.perform(postDelivery(VOLUNTEER, deliveryBody(vendorA, "van-1", line, "10", "0", null)))
				.andExpect(status().isForbidden());
		mvc.perform(as(VOLUNTEER, post("/api/v1/purchase-orders/{poId}/receipts", po))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"idempotencyKey\":\"k1\",\"lines\":[" + lineJson(line, "10", "0", null) + "]}"))
				.andExpect(status().isForbidden());
		mvc.perform(as(VOLUNTEER, get("/api/v1/purchase-orders/{poId}/receipts", po))).andExpect(status().isForbidden());
		assertNothingWritten();

		// The three who do hold it may read the screen.
		mvc.perform(as(STAFF, get("/api/v1/deliveries"))).andExpect(status().isOk());
		mvc.perform(as(MANAGER, get("/api/v1/deliveries"))).andExpect(status().isOk());
		mvc.perform(as(ADMIN, get("/api/v1/deliveries"))).andExpect(status().isOk());
	}

	// ---- Packs ------------------------------------------------------------------

	@Test
	@DisplayName("a line ordered in packs reports its pack label, one pack's size and the count, as the order page words them")
	void aPackLineReportsItsPack() throws Exception {
		UUID bag = admin.queryForObject("""
				INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, name, quantity, unit)
				VALUES (?, ?, 'Bag', 25, 'KG') RETURNING id
				""", UUID.class, tenant, rice);
		UUID po = sentPo(vendorA, null);
		UUID line = admin.queryForObject("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit, pack_size_id, pack_count)
				VALUES (?, ?, ?, 100, 'KG', ?, 4) RETURNING id
				""", UUID.class, tenant, po, rice, bag);

		JsonNode open = only(getJson(MANAGER, "/api/v1/deliveries").get("open"));
		assertThat(open.get("poLineId").asText()).isEqualTo(line.toString());
		assertThat(open.get("packLabel").asText()).isEqualTo("Bag (25 Kg)");
		assertThat(open.get("packQuantity").decimalValue()).isEqualByComparingTo("25");
		assertThat(open.get("packCount").decimalValue()).isEqualByComparingTo("4");
		assertThat(open.get("unit").asText()).isEqualTo("KG");

		// The same words the order page gives the same line, so the two screens cannot drift.
		JsonNode detail = getJson(MANAGER, "/api/v1/purchase-orders/" + po);
		assertThat(open.get("packLabel").asText()).isEqualTo(detail.get("lines").get(0).get("packLabel").asText());

		// A plain line carries none of the three.
		UUID plain = poLine(sentPo(vendorB, null), dal, "5");
		JsonNode plainLine = lineIn(getJson(MANAGER, "/api/v1/deliveries").get("open"), plain);
		assertThat(plainLine.get("packLabel").isNull()).isTrue();
		assertThat(plainLine.get("packQuantity").isNull()).isTrue();
		assertThat(plainLine.get("packCount").isNull()).isTrue();
	}

	// ---- No price at the gate (R-DEL-5) ------------------------------------------

	@Test
	@DisplayName("a delivery changes no list price and writes no price history")
	void aDeliveryWritesNoPrice() throws Exception {
		admin.update("""
				INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, last_price)
				VALUES (?, ?, ?, 45.00)
				""", tenant, vendorA, rice);
		UUID line = poLine(sentPo(vendorA, null), rice, "10");

		// A stale client that still sends a price: it must go nowhere.
		postJson(MANAGER, "{\"vendorId\":\"" + vendorA + "\",\"idempotencyKey\":\"van-1\",\"lines\":[{\"poLineId\":\""
				+ line + "\",\"receivedQty\":10,\"rejectedQty\":0,\"unitPrice\":99}]}");

		assertThat(admin.queryForObject(
				"SELECT last_price FROM vendor_supplies WHERE vendor_id = ? AND ingredient_id = ?",
				java.math.BigDecimal.class, vendorA, rice)).isEqualByComparingTo("45.00");
		assertThat(admin.queryForObject("SELECT count(*) FROM vendor_price_history", Integer.class)).isZero();
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM goods_receipt_lines WHERE unit_price IS NOT NULL", Integer.class)).isZero();
		// And nothing on the screen carries one.
		String screen = mvc.perform(as(MANAGER, get("/api/v1/deliveries"))).andReturn().getResponse().getContentAsString();
		assertThat(screen.toLowerCase()).doesNotContain("price");
	}

	// ---- The 30-day window (conductor's ruling, 2026-09-19) ----------------------

	@Test
	@DisplayName("the Received tab is the temple's today and the 29 days before it, and older pages go back 30 days at a time")
	void theReceivedWindowAndPagingBack() throws Exception {
		LocalDate today = LocalDate.now(TEMPLE);
		UUID line = poLine(sentPo(vendorA, null), rice, "100");

		// Four receipts on the edges: the first moment of day −29 (in), the last second of day −30
		// (out, on the first older page), the first moment of day −59 (first older page) and the last
		// second of day −60 (second older page). Each moved there by the schema owner.
		UUID in = receiptAt(line, "k-29", today.minusDays(29), "00:00:00");
		UUID out = receiptAt(line, "k-30", today.minusDays(30), "23:59:59");
		UUID page1 = receiptAt(line, "k-59", today.minusDays(59), "00:00:00");
		UUID page2 = receiptAt(line, "k-60", today.minusDays(60), "23:59:59");

		JsonNode view = getJson(MANAGER, "/api/v1/deliveries");
		assertThat(receiptIds(view.get("received"))).containsExactly(in.toString());
		assertThat(view.get("receivedFrom").asText()).isEqualTo(today.minusDays(29).toString());
		assertThat(view.get("hasOlder").asBoolean()).isTrue();
		assertThat(view.get("received").get(0).get("receivedOn").asText()).isEqualTo(today.minusDays(29).toString());
		// The line's history is whole, not cut to the window.
		assertThat(lineIn(view.get("receivedLines"), line).get("parts")).hasSize(4);

		JsonNode older = getJson(MANAGER, "/api/v1/deliveries/received?before=" + view.get("receivedFrom").asText());
		assertThat(receiptIds(older.get("received"))).containsExactly(out.toString(), page1.toString());
		assertThat(older.get("receivedFrom").asText()).isEqualTo(today.minusDays(59).toString());
		assertThat(older.get("hasOlder").asBoolean()).isTrue();

		JsonNode oldest = getJson(MANAGER, "/api/v1/deliveries/received?before=" + older.get("receivedFrom").asText());
		assertThat(receiptIds(oldest.get("received"))).containsExactly(page2.toString());
		assertThat(oldest.get("receivedFrom").asText()).isEqualTo(today.minusDays(89).toString());
		assertThat(oldest.get("hasOlder").asBoolean()).isFalse();
		assertThat(lineIn(oldest.get("receivedLines"), line).get("poLineId").asText()).isEqualTo(line.toString());
	}

	// ---- The wire shape, against frontend/lib/api.ts ------------------------------

	@Test
	@DisplayName("every field name matches the api.ts contract exactly: no more, no fewer")
	void fieldNamesMatchApiTs() throws Exception {
		UUID line = poLine(sentPo(vendorA, LocalDate.now(TEMPLE)), rice, "50");
		JsonNode recorded = postJson(MANAGER, deliveryBody(vendorA, "van-1", line, "30", "2", "SPOILED"));
		assertThat(names(recorded)).containsExactlyInAnyOrder("receiptIds");

		JsonNode view = getJson(MANAGER, "/api/v1/deliveries");
		// DeliveriesView
		assertThat(names(view)).containsExactlyInAnyOrder(
				"today", "open", "received", "receivedLines", "receivedFrom", "hasOlder");
		assertThat(view.get("today").asText()).isEqualTo(LocalDate.now(TEMPLE).toString());
		// DeliveryLineView
		assertThat(names(view.get("open").get(0))).containsExactlyInAnyOrder(
				"poLineId", "poId", "poNumber", "vendorId", "vendorName", "ingredientId", "itemName", "unit",
				"orderedQty", "receivedQty", "rejectedQty", "returnedQty", "stillToCome", "neededBy",
				"completedOn", "packLabel", "packQuantity", "packCount", "parts");
		assertThat(view.get("open").get(0).get("neededBy").asText()).isEqualTo(LocalDate.now(TEMPLE).toString());
		// DeliveryPartView
		assertThat(names(view.get("open").get(0).get("parts").get(0))).containsExactlyInAnyOrder(
				"receiptId", "receivedOn", "receivedQty", "rejectedQty", "rejectReason", "receivedByName");
		// DeliveryReceiptView
		assertThat(names(view.get("received").get(0))).containsExactlyInAnyOrder(
				"receiptId", "poId", "poNumber", "vendorId", "vendorName", "receivedOn", "receivedByName", "lines");
		// DeliveryReceiptLineView
		assertThat(names(view.get("received").get(0).get("lines").get(0))).containsExactlyInAnyOrder(
				"poLineId", "itemName", "unit", "receivedQty", "rejectedQty", "rejectReason", "returnedQty", "returns");
		// OlderDeliveriesView
		JsonNode older = getJson(MANAGER, "/api/v1/deliveries/received?before=" + view.get("receivedFrom").asText());
		assertThat(names(older)).containsExactlyInAnyOrder("received", "receivedLines", "receivedFrom", "hasOlder");
	}

	// ---------------------------------------------------------------------

	private void assertNothingWritten() {
		assertThat(admin.queryForObject("SELECT count(*) FROM goods_receipts", Integer.class)).isZero();
		assertThat(admin.queryForObject("SELECT count(*) FROM goods_receipt_lines", Integer.class)).isZero();
		assertThat(admin.queryForObject("SELECT count(*) FROM stock_movements", Integer.class)).isZero();
	}

	/** Records 1 Kg against {@code line} and moves the receipt to {@code time} on {@code day}, the temple's. */
	private UUID receiptAt(UUID line, String key, LocalDate day, String time) throws Exception {
		JsonNode r = postJson(MANAGER, deliveryBody(vendorA, key, line, "1", "0", null));
		UUID id = UUID.fromString(r.get("receiptIds").get(0).asText());
		admin.update("UPDATE goods_receipts SET received_at = (?::date + ?::time) AT TIME ZONE 'Asia/Kolkata' WHERE id = ?",
				day.toString(), time, id);
		return id;
	}

	private static List<String> receiptIds(JsonNode received) {
		List<String> ids = new ArrayList<>();
		received.forEach(r -> ids.add(r.get("receiptId").asText()));
		return ids;
	}

	private static List<String> names(JsonNode node) {
		List<String> out = new ArrayList<>();
		for (Iterator<String> it = node.fieldNames(); it.hasNext();) {
			out.add(it.next());
		}
		return out;
	}

	private static JsonNode only(JsonNode array) {
		assertThat(array).hasSize(1);
		return array.get(0);
	}

	private static JsonNode lineIn(JsonNode lines, UUID poLineId) {
		for (JsonNode l : lines) {
			if (l.get("poLineId").asText().equals(poLineId.toString())) {
				return l;
			}
		}
		throw new AssertionError("no line " + poLineId + " in " + lines);
	}

	private JsonNode postJson(String token, String body) throws Exception {
		String json = mvc.perform(postDelivery(token, body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(json);
	}

	private JsonNode getJson(String token, String path) throws Exception {
		String json = mvc.perform(as(token, get(path)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(json);
	}

	private MockHttpServletRequestBuilder postDelivery(String token, String body) {
		return as(token, post("/api/v1/deliveries")).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer " + token);
	}

	private static String deliveryBody(UUID vendor, String key, UUID line, String received, String rejected,
			String reason) {
		return "{\"vendorId\":\"" + vendor + "\",\"idempotencyKey\":\"" + key + "\",\"lines\":["
				+ lineJson(line, received, rejected, reason) + "]}";
	}

	private static String lineJson(UUID line, String received, String rejected, String reason) {
		String r = reason == null ? "" : ",\"rejectReason\":\"" + reason + "\"";
		return "{\"poLineId\":\"" + line + "\",\"receivedQty\":" + received + ",\"rejectedQty\":" + rejected + r + "}";
	}

	private UUID user(String uid, String name, String email, String phone, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE') RETURNING id
				""", UUID.class, tenant, uid, name, email, phone, role);
	}

	private UUID ingredient(String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', 'KG') RETURNING id
				""", UUID.class, tenant, name);
	}

	private UUID vendor(String name, String phone) {
		return admin.queryForObject("INSERT INTO vendors (tenant_id, name, phone) VALUES (?, ?, ?) RETURNING id",
				UUID.class, tenant, name, phone);
	}

	private UUID sentPo(UUID vendor, LocalDate neededBy) {
		return insertPo(vendor, "SENT", neededBy);
	}

	private UUID insertPo(UUID vendor, String status, LocalDate neededBy) {
		poSeq++;
		return admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, sent_at, created_by, needed_by)
				VALUES (?, ?, ?, ?, CASE WHEN ? = 'DRAFT' THEN NULL ELSE now() END, ?, ?) RETURNING id
				""", UUID.class, tenant, "PO-2026-09" + String.format("%02d", poSeq), vendor, status, status,
				managerId, neededBy);
	}

	private UUID poLine(UUID poId, UUID ingredient, String qty) {
		return admin.queryForObject("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit)
				VALUES (?, ?, ?, ?::numeric, 'KG') RETURNING id
				""", UUID.class, tenant, poId, ingredient, qty);
	}

	private java.math.BigDecimal onHand(UUID ingredient) {
		return admin.queryForObject(
				"SELECT COALESCE(SUM(quantity), 0) FROM stock_movements WHERE ingredient_id = ?",
				java.math.BigDecimal.class, ingredient);
	}
}
