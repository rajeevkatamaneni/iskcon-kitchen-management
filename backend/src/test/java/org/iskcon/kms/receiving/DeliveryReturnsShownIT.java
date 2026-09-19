package org.iskcon.kms.receiving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * The Received tab's Returned column says what the approved mock says: "Paneer 1 Kg, spoiled,
 * 17 Sept" (T-285; VERIFY-C defect 4). Before this the Deliveries view carried only the summed
 * {@code returnedQty}, so the screen could name the item and the amount and never the reason or
 * the day.
 *
 * <p>Each delivered line now carries {@code returns}: every return made against it, oldest first,
 * as {@code {quantity, reason, returnedOn}}, which is the shape {@code frontend/lib/api.ts}
 * reserves for {@code DeliveryReturnView}. The returns are made here through the real endpoint,
 * {@code POST /goods-receipts/{id}/returns}, so the rows are the ones {@code GoodsReturnService}
 * writes and not a hand-made imitation of them.
 *
 * <p>Its own class rather than more tests in {@code DeliveriesIT}, which belongs to another task;
 * the setup is the same shape so the two read alike.
 */
@AutoConfigureMockMvc
class DeliveryReturnsShownIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** The temple's zone, set on the tenant below. The server reads it; the test states it. */
	private static final ZoneId TEMPLE = ZoneId.of("Asia/Kolkata");

	private static final String MANAGER = "manager-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID managerId;
	private UUID rice;
	private UUID dal;
	private UUID vendor;
	private int poSeq;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		managerId = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-manager', 'Karuna Murti Das', 'manager@example.com', '+919876500091',
						'KITCHEN_MANAGER', 'ACTIVE') RETURNING id
				""", UUID.class, tenant);
		stubVerifier.accept(MANAGER,
				new TokenVerifier.VerifiedSubject("uid-manager", "manager@example.com", "+919876500091"));
		rice = ingredient("Rice");
		dal = ingredient("Toor dal");
		vendor = admin.queryForObject(
				"INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Balaji Traders', '+919812345601') RETURNING id",
				UUID.class, tenant);
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
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("each return on a delivered line comes with its amount, its reason and the temple's day, oldest first; a line with none has an empty list")
	void returnsCarryReasonAndDay() throws Exception {
		UUID po = sentPo();
		UUID riceLine = poLine(po, rice, "50");
		UUID dalLine = poLine(po, dal, "20");
		UUID receipt = deliver(riceLine, "30", dalLine, "20");
		UUID riceReceiptLine = receiptLine(receipt, riceLine);

		// Two returns against the rice, on two different days. The first is moved to 00:30 in
		// Bengaluru, which is still the day before in UTC: it must read as the temple's day.
		UUID spoiled = returnGoods(receipt, riceReceiptLine, "ret-1", "1", "SPOILED");
		UUID damaged = returnGoods(receipt, riceReceiptLine, "ret-2", "2.5", "DAMAGED");
		LocalDate today = LocalDate.now(TEMPLE);
		LocalDate firstDay = today.minusDays(2);
		moveReturn(spoiled, firstDay, "00:30");
		moveReturn(damaged, today, "10:00");

		JsonNode view = getJson("/api/v1/deliveries");
		JsonNode lines = only(view.get("received")).get("lines");

		JsonNode riceRow = lineIn(lines, riceLine);
		assertThat(riceRow.get("returnedQty").decimalValue()).isEqualByComparingTo("3.5");
		JsonNode returns = riceRow.get("returns");
		assertThat(returns).hasSize(2);
		assertThat(returns.get(0).get("quantity").decimalValue()).isEqualByComparingTo("1");
		assertThat(returns.get(0).get("reason").asText()).isEqualTo("SPOILED");
		assertThat(returns.get(0).get("returnedOn").asText()).isEqualTo(firstDay.toString());
		assertThat(returns.get(1).get("quantity").decimalValue()).isEqualByComparingTo("2.5");
		assertThat(returns.get(1).get("reason").asText()).isEqualTo("DAMAGED");
		assertThat(returns.get(1).get("returnedOn").asText()).isEqualTo(today.toString());

		JsonNode dalRow = lineIn(lines, dalLine);
		assertThat(dalRow.get("returnedQty").decimalValue()).isEqualByComparingTo("0");
		assertThat(dalRow.get("returns").isArray()).isTrue();
		assertThat(dalRow.get("returns")).isEmpty();
	}

	@Test
	@DisplayName("the order is by when it went back, not by the order it was typed in: a return moved earlier is listed first")
	void oldestFirstByDateNotByInsertion() throws Exception {
		UUID riceLine = poLine(sentPo(), rice, "50");
		UUID receipt = deliver(riceLine, "30", null, null);
		UUID rl = receiptLine(receipt, riceLine);
		UUID later = returnGoods(receipt, rl, "ret-a", "1", "WRONG_ITEM");
		UUID earlier = returnGoods(receipt, rl, "ret-b", "2", "NOT_DELIVERED");
		LocalDate today = LocalDate.now(TEMPLE);
		moveReturn(later, today, "09:00");
		moveReturn(earlier, today.minusDays(1), "09:00");

		JsonNode returns = lineIn(only(getJson("/api/v1/deliveries").get("received")).get("lines"), riceLine)
				.get("returns");
		assertThat(returns.get(0).get("reason").asText()).isEqualTo("NOT_DELIVERED");
		assertThat(returns.get(1).get("reason").asText()).isEqualTo("WRONG_ITEM");
	}

	@Test
	@DisplayName("older deliveries carry their returns too: the 30 days before the first page read the same way")
	void olderPageCarriesReturns() throws Exception {
		UUID riceLine = poLine(sentPo(), rice, "50");
		UUID receipt = deliver(riceLine, "30", null, null);
		UUID rl = receiptLine(receipt, riceLine);
		UUID ret = returnGoods(receipt, rl, "ret-old", "4", "SPOILED");
		LocalDate delivered = LocalDate.now(TEMPLE).minusDays(40);
		admin.update("UPDATE goods_receipts SET received_at = (?::date + time '08:00') AT TIME ZONE 'Asia/Kolkata' WHERE id = ?",
				delivered.toString(), receipt);
		moveReturn(ret, delivered.plusDays(1), "11:00");

		JsonNode view = getJson("/api/v1/deliveries");
		assertThat(view.get("received")).isEmpty();
		JsonNode older = getJson("/api/v1/deliveries/received?before=" + view.get("receivedFrom").asText());
		JsonNode returns = lineIn(only(older.get("received")).get("lines"), riceLine).get("returns");
		assertThat(returns).hasSize(1);
		assertThat(returns.get(0).get("reason").asText()).isEqualTo("SPOILED");
		assertThat(returns.get(0).get("returnedOn").asText()).isEqualTo(delivered.plusDays(1).toString());
	}

	@Test
	@DisplayName("the wire shape matches api.ts exactly: DeliveryReceiptLineView gains returns, and DeliveryReturnView is quantity, reason, returnedOn")
	void wireShapeMatchesApiTs() throws Exception {
		UUID riceLine = poLine(sentPo(), rice, "50");
		UUID receipt = deliver(riceLine, "30", null, null);
		returnGoods(receipt, receiptLine(receipt, riceLine), "ret-1", "1", "OTHER");

		JsonNode line = only(only(getJson("/api/v1/deliveries").get("received")).get("lines"));
		assertThat(names(line)).containsExactlyInAnyOrder(
				"poLineId", "itemName", "unit", "receivedQty", "rejectedQty", "rejectReason", "returnedQty", "returns");
		assertThat(names(only(line.get("returns")))).containsExactlyInAnyOrder("quantity", "reason", "returnedOn");
	}

	// ---------------------------------------------------------------------

	/** Records one van through the Deliveries endpoint; the second line is optional. */
	private UUID deliver(UUID line1, String qty1, UUID line2, String qty2) throws Exception {
		String lines = lineJson(line1, qty1) + (line2 == null ? "" : "," + lineJson(line2, qty2));
		String body = "{\"vendorId\":\"" + vendor + "\",\"idempotencyKey\":\"van-" + UUID.randomUUID()
				+ "\",\"lines\":[" + lines + "]}";
		String json = mvc.perform(as(post("/api/v1/deliveries")).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(JSON.readTree(json).get("receiptIds").get(0).asText());
	}

	private static String lineJson(UUID line, String qty) {
		return "{\"poLineId\":\"" + line + "\",\"receivedQty\":" + qty + ",\"rejectedQty\":0}";
	}

	private UUID receiptLine(UUID receipt, UUID poLine) {
		return admin.queryForObject("SELECT id FROM goods_receipt_lines WHERE receipt_id = ? AND po_line_id = ?",
				UUID.class, receipt, poLine);
	}

	/** Sends goods back through the real endpoint, as the order page does. */
	private UUID returnGoods(UUID receipt, UUID receiptLine, String key, String qty, String reason) throws Exception {
		String body = "{\"idempotencyKey\":\"" + key + "\",\"receiptLineId\":\"" + receiptLine + "\",\"quantity\":"
				+ qty + ",\"reason\":\"" + reason + "\"}";
		String json = mvc.perform(as(post("/api/v1/goods-receipts/{id}/returns", receipt))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(JSON.readTree(json).get("id").asText());
	}

	/**
	 * Moves a return to {@code time} on the temple's {@code day}. Done by the schema owner, because the
	 * table is append-only to the application and nothing else can.
	 */
	private void moveReturn(UUID id, LocalDate day, String time) {
		admin.update("UPDATE goods_returns SET returned_at = (?::date + ?::time) AT TIME ZONE 'Asia/Kolkata' WHERE id = ?",
				day.toString(), time, id);
	}

	private JsonNode getJson(String path) throws Exception {
		String json = mvc.perform(as(get(path)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(json);
	}

	private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer " + MANAGER);
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

	private UUID ingredient(String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', 'KG') RETURNING id
				""", UUID.class, tenant, name);
	}

	private UUID sentPo() {
		poSeq++;
		return admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, sent_at, created_by, needed_by)
				VALUES (?, ?, ?, 'SENT', now(), ?, ?) RETURNING id
				""", UUID.class, tenant, "PO-2026-09" + String.format("%02d", poSeq), vendor, managerId,
				LocalDate.now(TEMPLE));
	}

	private UUID poLine(UUID poId, UUID ingredient, String qty) {
		return admin.queryForObject("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit)
				VALUES (?, ?, ?, ?::numeric, 'KG') RETURNING id
				""", UUID.class, tenant, poId, ingredient, qty);
	}
}
