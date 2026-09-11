package org.iskcon.kms.purchaseorder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * A purchase-order line that names something the catalogue has never heard of (T-024, D-1).
 *
 * <p>Four plastic stools from a furniture shop and two extension cords from an electrical one. The
 * temple buys them on a purchase order, from a vendor, against a bill — and wants the opposite of a
 * catalogue entry: nothing invented in {@code ingredients}, and nothing landing in stock.
 *
 * <p>The column change is the small half. The interesting half is the six consumers that read a PO
 * line and would each have failed differently on a null {@code ingredient_id} — one of them, the
 * inner join on the detail query, entirely silently. Every test below is about one of those.
 */
@AutoConfigureMockMvc
@Import(DescribedPurchaseLineIT.StubVerifierConfiguration.class)
class DescribedPurchaseLineIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** The temple's own zone, which is the day an arrival is recorded in — never the JVM's. */
	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID rice;
	private UUID vendorA;

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
		rice = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Rice', 'Grains', 'KG') RETURNING id
				""", UUID.class, tenant);
		vendorA = admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, 'Govind Wholesale', '+919812345678')
				RETURNING id
				""", UUID.class, tenant);
		stubVerifier.accept("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		// Sending a PO auto-generates its sheet and printing one caches its label set per tenant, so
		// both hold the tenant row down through a FK. Left out of the first version of this file,
		// which made the DELETE FROM tenants below fail and left the slug behind — poisoning the
		// setUp of every other IT in the same run, none of which were anything to do with T-024.
		admin.execute("DELETE FROM documents");
		admin.execute("DELETE FROM po_label_translations");
		admin.execute("DELETE FROM translation_glossary");
		admin.execute("DELETE FROM goods_receipt_lines");
		admin.execute("DELETE FROM goods_receipts");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM shopping_list_lines");
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

	// ---- The detail query, which is the dangerous one --------------------

	@Test
	@DisplayName("an order with one ingredient line and one described line saves, and BOTH come back")
	void bothLinesSurviveTheDetailQuery() throws Exception {
		String id = createMixedOrder();

		JsonNode po = getDetail(id);
		// This is the whole regression. The detail query was an INNER JOIN on ingredients, which is
		// correct only while ingredient_id is NOT NULL — the moment a line is described instead, an
		// inner join drops it with no error, no log and no count. The order would simply have had a
		// line missing, on this screen and on the vendor sheet, and nobody would have found out
		// until a vendor delivered something nobody could see they had ordered.
		assert po.get("lines").size() == 2
				: "both lines must come back; an inner join would silently return " + po.get("lines").size();

		Map<String, JsonNode> bySubject = new HashMap<>();
		po.get("lines").forEach(l -> bySubject.put(
				l.get("ingredientName").isNull() ? l.get("description").asText()
						: l.get("ingredientName").asText(),
				l));

		JsonNode riceLine = bySubject.get("Rice");
		assert riceLine != null : "the ingredient line";
		assert riceLine.get("description").isNull() : "an ingredient line describes nothing";
		assert riceLine.get("ingredientId").asText().equals(rice.toString());

		JsonNode stool = bySubject.get("Plastic stool");
		assert stool != null : "the described line";
		assert stool.get("ingredientId").isNull() : "a described line names no ingredient";
		assert stool.get("ingredientName").isNull() : "and has no catalogue name";
		assert stool.get("quantity").asDouble() == 4.0;
		assert stool.get("unit").asText().equals("PIECES") : "four stools is four pieces";

		// And nothing was invented in the catalogue to make this work, which is the point of D-1.
		assert admin.queryForObject("SELECT count(*) FROM ingredients", Integer.class) == 1
				: "the catalogue still holds only Rice";
	}

	// ---- The exclusivity rule -------------------------------------------

	@Test
	@DisplayName("a line with both an ingredient and a description is refused with KMS-400128")
	void bothSubjectsIsRefused() throws Exception {
		mvc.perform(authed(post("/api/v1/purchase-orders"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"vendorId\":\"" + vendorA + "\",\"lines\":["
								+ "{\"ingredientId\":\"" + rice + "\",\"description\":\"Plastic stool\","
								+ "\"quantity\":4,\"unit\":\"PIECES\"}]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400128"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("Line 1"));

		assert admin.queryForObject("SELECT count(*) FROM purchase_orders", Integer.class) == 0
				: "the order is refused whole, not written and then repaired";
	}

	@Test
	@DisplayName("a line with neither an ingredient nor a description is refused with KMS-400128")
	void neitherSubjectIsRefused() throws Exception {
		mvc.perform(authed(post("/api/v1/purchase-orders"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"vendorId\":\"" + vendorA + "\",\"lines\":["
								+ "{\"quantity\":4,\"unit\":\"PIECES\"}]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400128"));

		assert admin.queryForObject("SELECT count(*) FROM purchase_orders", Integer.class) == 0;
	}

	@Test
	@DisplayName("a description made of whitespace is nothing described, and is refused too")
	void blankDescriptionIsRefused() throws Exception {
		mvc.perform(authed(post("/api/v1/purchase-orders"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"vendorId\":\"" + vendorA + "\",\"lines\":["
								+ "{\"ingredientId\":null,\"description\":\"   \","
								+ "\"quantity\":4,\"unit\":\"PIECES\"}]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400128"));
	}

	@Test
	@DisplayName("the database refuses the same two shapes, whatever the application does")
	void theConstraintIsInTheDatabase() {
		UUID poId = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, created_by)
				VALUES (?, 'PO-DB-0001', ?, 'DRAFT', (SELECT id FROM users WHERE tenant_id = ? LIMIT 1))
				RETURNING id
				""", UUID.class, tenant, vendorA, tenant);

		// Both subjects.
		assertRefusedByConstraint(() -> admin.update("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, description, quantity, unit)
				VALUES (?, ?, ?, 'Plastic stool', 4, 'PIECES')
				""", tenant, poId, rice), "a line naming both");

		// Neither.
		assertRefusedByConstraint(() -> admin.update("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, quantity, unit)
				VALUES (?, ?, 4, 'PIECES')
				""", tenant, poId), "a line naming neither");

		// A description of spaces, which would otherwise satisfy "description IS NOT NULL".
		assertRefusedByConstraint(() -> admin.update("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, description, quantity, unit)
				VALUES (?, ?, '   ', 4, 'PIECES')
				""", tenant, poId), "a blank description");
	}

	// ---- The unit-family check ------------------------------------------

	@Test
	@DisplayName("a described line skips the unit-family check; a real ingredient still faces it")
	void unitFamilyIsSkippedNotWeakened() throws Exception {
		// Skipped: there is no catalogue row to compare a stool's PIECES against, and the line saves.
		String id = createMixedOrder();
		assert getDetail(id).get("lines").size() == 2;

		// Not weakened: rice is held in Kg, and asking for it in litres is still refused. The
		// alternative fix — making IngredientUnits.find() lenient about a null id — would have let
		// this through too, which is why the skip lives at the caller.
		mvc.perform(authed(post("/api/v1/purchase-orders"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"vendorId\":\"" + vendorA + "\",\"lines\":["
								+ "{\"ingredientId\":\"" + rice + "\",\"quantity\":5,\"unit\":\"L\"}]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400013"));
	}

	// ---- Receiving -------------------------------------------------------

	@Test
	@DisplayName("the ingredient line receives; the described line is refused with KMS-400129 and stocks nothing")
	void describedLineIsNeverReceivedIntoStock() throws Exception {
		String id = createMixedOrder();
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id))).andExpect(status().isNoContent());

		UUID riceLine = lineIdFor(id, "rice");
		UUID stoolLine = lineIdFor(id, "stool");

		// Trying to take the stools into stock is refused, by name, with something to do instead.
		mvc.perform(receive(UUID.fromString(id), "{\"idempotencyKey\":\"k0\",\"lines\":[{\"poLineId\":\""
						+ stoolLine + "\",\"receivedQty\":4,\"rejectedQty\":0}]}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400129"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("Plastic stool"));

		// The refusal wrote nothing at all — not a header, not a line, not a movement.
		assert admin.queryForObject("SELECT count(*) FROM goods_receipts", Integer.class) == 0
				: "a refused receipt leaves no header behind";
		assert admin.queryForObject("SELECT count(*) FROM stock_movements", Integer.class) == 0;

		// The rice receives normally, with a price, which is the ordinary path still working.
		mvc.perform(receive(UUID.fromString(id), "{\"idempotencyKey\":\"k1\",\"lines\":[{\"poLineId\":\""
						+ riceLine + "\",\"receivedQty\":30,\"rejectedQty\":0,\"unitPrice\":58.50}]}"))
				.andExpect(status().isCreated());

		assert admin.queryForObject(
				"SELECT count(*) FROM stock_movements WHERE ingredient_id = ?", Integer.class, rice) == 1
				: "the rice went into the ledger";

		// Nothing was written for the stools anywhere the store room reads. Asserted, not assumed —
		// and asserted on the columns rather than on a count of everything, so a future stray row
		// cannot make this pass by accident.
		assert admin.queryForObject("SELECT count(*) FROM stock_movements", Integer.class) == 1
				: "exactly one movement, and it is the rice";
		assert admin.queryForObject(
				"SELECT count(*) FROM goods_receipt_lines WHERE po_line_id = ?", Integer.class, stoolLine) == 0
				: "no receipt line for the described line";
		assert admin.queryForObject("SELECT count(*) FROM vendor_supplies", Integer.class) == 1
				: "one supply row, for the rice; a stool is not something a vendor supplies in the catalogue sense";
		assert admin.queryForObject(
				"SELECT last_price FROM vendor_supplies WHERE ingredient_id = ?", BigDecimal.class, rice)
				.compareTo(new BigDecimal("58.50")) == 0;

		// CHANGED ON PURPOSE AT T-066, and the assertion it replaces mattered, so here is why.
		//
		// This used to assert RECEIVED. T-024 left described lines out of the coverage arithmetic
		// altogether, for one stated reason: there was no way to account for a described line at
		// all, so counting one would have pinned the order at PARTIALLY_RECEIVED for ever — which
		// the shopping list and the vendor scorecard both read as "still outstanding".
		//
		// T-066 removes that reason. There is now an action, it takes one press, and the storekeeper
		// is standing at the lorry with the answer. So the exception goes and the rule is uniform:
		// an order is finished when every line on it is. The rice is received and the stools are
		// not yet accounted for, so this order is genuinely part-done — a truthful open order
		// rather than a silent claim that an order is complete while a line on it has never been
		// confirmed by anybody. aMixedOrderClosesOnBothHalves presses the button and closes it.
		JsonNode after = getDetail(id);
		assert after.get("order").get("status").asText().equals("PARTIALLY_RECEIVED")
				: "status was " + after.get("order").get("status").asText();

		// And the skip is visible on the trail rather than silent.
		assert after.get("events").findValuesAsText("eventType").contains("DESCRIBED_LINES_NOT_STOCKED")
				: "the trail must say what was not taken into stock";
		assert after.get("events").toString().contains("Plastic stool")
				: "and name it";
	}

	// ---- "These arrived" (T-066) -----------------------------------------

	@Test
	@DisplayName("an order of nothing but described lines is closed by recording that they arrived, and stocks nothing")
	void aDescribedOnlyOrderIsClosedByAnArrival() throws Exception {
		String id = describedOnlyOrder();
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id))).andExpect(status().isNoContent());

		UUID cord = lineIdFor(id, "extension cord");
		mvc.perform(arrivals(id, cord)).andExpect(status().isNoContent());

		JsonNode after = getDetail(id);
		assert after.get("order").get("status").asText().equals("RECEIVED")
				: "an order whose every line is accounted for is finished; status was "
						+ after.get("order").get("status").asText();

		// This is the whole defect T-066 was raised for. The scorecard's open-orders aging bucket is
		// literally this predicate (VendorPerformanceService.countOpenOrders), and before T-066 an
		// order like this one could never leave it: it aged past 31 days and read as a supplier
		// sitting on an order since last year, for ever, with no action anywhere that could close it.
		assert admin.queryForObject(
				"SELECT count(*) FROM purchase_orders WHERE id = ?::uuid"
						+ " AND status IN ('SENT', 'PARTIALLY_RECEIVED')",
				Integer.class, id) == 0
				: "the order must leave the open-orders aging bucket";

		// A status transition and NOT a stock movement, which is Rajeev's ruling word for word.
		// Asserted on each table the store room actually reads, rather than on the absence of an
		// error — "no exception was thrown" would pass just as happily if this had booked four
		// extension cords into inventory.
		assert admin.queryForObject("SELECT count(*) FROM stock_movements", Integer.class) == 0
				: "an arrival is not a stock movement";
		assert admin.queryForObject("SELECT count(*) FROM goods_receipts", Integer.class) == 0
				: "an arrival is not a goods receipt";
		assert admin.queryForObject("SELECT count(*) FROM goods_receipt_lines", Integer.class) == 0;
		assert admin.queryForObject("SELECT count(*) FROM inventory_items", Integer.class) == 0
				: "nothing is on hand: the store room does not track an extension cord";
		assert admin.queryForObject("SELECT count(*) FROM ingredients", Integer.class) == 1
				: "and still nothing was invented in the catalogue to make it work";

		// The date is on the line, in the temple's own day, and it comes back on the wire — the
		// vendor scorecard judges on-time against it and the screen reads it to stop offering the
		// line again.
		JsonNode line = after.get("lines").get(0);
		assert !line.get("arrivedOn").isNull() : "the line carries the day it arrived";
		assert line.get("arrivedOn").asText().equals(LocalDate.now(TEMPLE_ZONE).toString())
				: "recorded in the temple's day, not the JVM's: " + line.get("arrivedOn").asText();

		// Who and when, beside it, because an arrival nobody is accountable for is not a record.
		assert admin.queryForObject(
				"SELECT count(*) FROM purchase_order_lines WHERE po_id = ?::uuid"
						+ " AND arrived_recorded_at IS NOT NULL AND arrived_recorded_by IS NOT NULL",
				Integer.class, id) == 1;

		assert after.get("events").findValuesAsText("eventType").contains("ARRIVED")
				: "the trail must say it arrived";
		assert after.get("events").toString().contains("Extension cord") : "and name it";
	}

	@Test
	@DisplayName("a mixed order closes once its rice is received AND its stools are recorded as arrived")
	void aMixedOrderClosesOnBothHalves() throws Exception {
		String id = createMixedOrder();
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id))).andExpect(status().isNoContent());
		UUID riceLine = lineIdFor(id, "rice");
		UUID stoolLine = lineIdFor(id, "stool");

		// The stools first, this time, because the order the two halves happen in must not matter:
		// the hardware shop may well beat the rice lorry.
		mvc.perform(arrivals(id, stoolLine)).andExpect(status().isNoContent());
		assert getDetail(id).get("order").get("status").asText().equals("PARTIALLY_RECEIVED")
				: "the rice is still owed";

		mvc.perform(receive(UUID.fromString(id), "{\"idempotencyKey\":\"k1\",\"lines\":[{\"poLineId\":\""
						+ riceLine + "\",\"receivedQty\":30,\"rejectedQty\":0}]}"))
				.andExpect(status().isCreated());

		JsonNode after = getDetail(id);
		assert after.get("order").get("status").asText().equals("RECEIVED")
				: "both halves are accounted for; status was " + after.get("order").get("status").asText();

		// Exactly one movement, and it is the rice. The stools did not follow the order into stock
		// on the way past.
		assert admin.queryForObject("SELECT count(*) FROM stock_movements", Integer.class) == 1;
		assert admin.queryForObject(
				"SELECT count(*) FROM stock_movements WHERE ingredient_id = ?", Integer.class, rice) == 1;

		// And a line already accounted for is not named again as outstanding on the receipt's trail.
		String trail = after.get("events").toString();
		assert !trail.contains("record on the order whether they arrived")
				: "the receipt should not report an already-arrived line as still outstanding: " + trail;
	}

	@Test
	@DisplayName("an arrival is refused on a draft, on a catalogue line, and a second time")
	void anArrivalIsRefusedWhereItWouldBeUntrue() throws Exception {
		String id = createMixedOrder();
		UUID riceLine = lineIdFor(id, "rice");
		UUID stoolLine = lineIdFor(id, "stool");

		// On a draft: nothing has been sent to the vendor, so nothing can have come back.
		mvc.perform(arrivals(id, stoolLine))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400051"));

		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id))).andExpect(status().isNoContent());

		// Against the rice: a catalogue line is accounted for by the ledger and by nothing else.
		// Allowing a date here would give "is this line covered" two competing answers, and the
		// first time they disagreed the order's status would depend on which query ran.
		mvc.perform(arrivals(id, riceLine))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-400030"));
		assert admin.queryForObject(
				"SELECT count(*) FROM purchase_order_lines WHERE arrived_on IS NOT NULL",
				Integer.class) == 0
				: "a refused arrival writes nothing at all";

		// Both at once: the rice is refused, and the refusal takes the stools down with it rather
		// than leaving the order half-recorded.
		mvc.perform(arrivals(id, stoolLine, riceLine)).andExpect(status().isNotFound());
		assert admin.queryForObject(
				"SELECT count(*) FROM purchase_order_lines WHERE arrived_on IS NOT NULL",
				Integer.class) == 0
				: "the whole request rolls back, so the stools are not quietly recorded";

		mvc.perform(arrivals(id, stoolLine)).andExpect(status().isNoContent());

		// Twice: the second press has nothing left to record. A double-click must not overwrite the
		// day the goods actually turned up with today's.
		mvc.perform(arrivals(id, stoolLine))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-400030"));
	}

	@Test
	@DisplayName("the database refuses an arrival on a catalogue line whatever the application does")
	void theArrivalConstraintsAreInTheDatabase() {
		UUID poId = admin.queryForObject(
				"INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, created_by)"
						+ " VALUES (?, 'PO-DB-0002', ?, 'SENT',"
						+ " (SELECT id FROM users WHERE tenant_id = ? LIMIT 1)) RETURNING id",
				UUID.class, tenant, vendorA, tenant);
		UUID riceLine = admin.queryForObject(
				"INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit)"
						+ " VALUES (?, ?, ?, 30, 'KG') RETURNING id",
				UUID.class, tenant, poId, rice);
		UUID stoolLine = admin.queryForObject(
				"INSERT INTO purchase_order_lines (tenant_id, po_id, description, quantity, unit)"
						+ " VALUES (?, ?, 'Plastic stool', 4, 'PIECES') RETURNING id",
				UUID.class, tenant, poId);

		assertRefusedBy("po_lines_only_a_described_line_arrives", () -> admin.update(
				"UPDATE purchase_order_lines SET arrived_on = CURRENT_DATE,"
						+ " arrived_recorded_at = now(),"
						+ " arrived_recorded_by = (SELECT id FROM users LIMIT 1) WHERE id = ?",
				riceLine), "an arrival on a catalogue line");

		// A date with nobody's name against it is a record of an arrival nobody is accountable for.
		assertRefusedBy("po_lines_arrival_is_recorded_whole", () -> admin.update(
				"UPDATE purchase_order_lines SET arrived_on = CURRENT_DATE WHERE id = ?", stoolLine),
				"an arrival with no actor and no timestamp");
	}

	// ---- The shopping list ----------------------------------------------

	@Test
	@DisplayName("a described line stays out of the shopping list's outstanding map entirely")
	void shoppingListIsUnaffected() throws Exception {
		// An order carrying ONLY a described line, sent, so it is live and outstanding. Nothing else
		// is demanded: no meal plan, no low stock, no ingredient line. So the correct answer is that
		// this order contributes nothing at all to the shopping list.
		String id = describedOnlyOrder();
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id))).andExpect(status().isNoContent());

		// What T-024 guards, restated for a list that is computed rather than stored (T-132).
		//
		// Two of the shopping list's queries key a map by purchase_order_lines.ingredient_id, which
		// is null on a described line, and a LinkedHashMap takes a null key perfectly happily. Every
		// described line on every live order would therefore collapse into one bucket under `null`,
		// adding four plastic stools to a reel of extension cord in base units — and whatever
		// ingredient row later asked that map for its outstanding quantity would get an answer
		// computed from furniture. Both queries exclude the null in SQL, and this is the assertion
		// that says so.
		//
		// The old shape of this test is worth recording, because the defect it caught is gone
		// along with the mechanism: regeneration used to end with `DELETE ... WHERE edited = false
		// AND ingredient_id NOT IN (?)`, and `NOT IN` with a NULL in the list is never true for any
		// row, so a null key silently stopped every stale suggestion from ever being dropped. There
		// is no delete now, and nothing stale to drop, because the list is recomputed on every read
		// — which is a stronger answer to the same problem than the fix was.
		JsonNode lines = JSON.readTree(mvc.perform(authed(get("/api/v1/shopping-list")))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assert lines.isArray() && lines.size() == 0
				: "a described line suggests nothing to buy; got " + lines;
	}

	// ---- The printed sheet ----------------------------------------------

	@Test
	@DisplayName("the vendor sheet prints the described line's words, and does not NPE in the glossary")
	void theSheetRendersADescribedLine() throws Exception {
		String id = createMixedOrder();
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id))).andExpect(status().isNoContent());

		// English: the danger here was quiet rather than loud. The template escapes a null name to
		// "" (PurchaseOrderSheetTemplate.esc), so the vendor would have been handed a sheet with a
		// quantity, a price and a blank where the item should be.
		String html = mvc.perform(authed(get("/api/v1/purchase-orders/{id}/print", id)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assert html.contains("Plastic stool") : "the described line must be on the sheet";
		assert html.contains("Rice");

		// Non-English is where it was loud: translateLines called toLowerCase() on each name for the
		// glossary lookup, and a null name threw NullPointerException — a 500 on a screen somebody
		// presses to hand a vendor a piece of paper.
		String kannada = mvc.perform(authed(get("/api/v1/purchase-orders/{id}/print", id).param("language", "kn")))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assert !kannada.isBlank() : "a non-English sheet must render at all";
	}

	// ---------------------------------------------------------------------

	/** One PO carrying Rice (30 Kg) and four plastic stools, in that order. */
	private String createMixedOrder() throws Exception {
		String body = "{\"vendorId\":\"" + vendorA + "\",\"lines\":["
				+ "{\"ingredientId\":\"" + rice + "\",\"description\":null,\"quantity\":30,\"unit\":\"KG\",\"expectedPrice\":45},"
				+ "{\"ingredientId\":null,\"description\":\"Plastic stool\",\"quantity\":4,\"unit\":\"PIECES\",\"expectedPrice\":250}]}";
		String response = mvc.perform(authed(post("/api/v1/purchase-orders"))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(response).get("id").asText();
	}

	/** One PO carrying nothing but a described line. */
	private String describedOnlyOrder() throws Exception {
		String body = "{\"vendorId\":\"" + vendorA + "\",\"lines\":["
				+ "{\"ingredientId\":null,\"description\":\"Extension cord\",\"quantity\":2,\"unit\":\"PIECES\"}]}";
		String response = mvc.perform(authed(post("/api/v1/purchase-orders"))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(response).get("id").asText();
	}

	/** The id of the line whose subject contains {@code needle}, read back through the API. */
	private UUID lineIdFor(String poId, String needle) throws Exception {
		for (JsonNode l : getDetail(poId).get("lines")) {
			String subject = l.get("ingredientName").isNull()
					? l.get("description").asText() : l.get("ingredientName").asText();
			if (subject.toLowerCase().contains(needle)) {
				return UUID.fromString(l.get("id").asText());
			}
		}
		throw new AssertionError("no line matching " + needle);
	}

	private JsonNode getDetail(String id) throws Exception {
		return JSON.readTree(mvc.perform(authed(get("/api/v1/purchase-orders/{id}", id)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	/** POST the "these arrived" acknowledgement for one or more of the order's lines (T-066). */
	private MockHttpServletRequestBuilder arrivals(String poId, UUID... lineIds) {
		String ids = Arrays.stream(lineIds).map(u -> "\"" + u + "\"").collect(Collectors.joining(","));
		return authed(post("/api/v1/purchase-orders/{poId}/arrivals", poId))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"poLineIds\":[" + ids + "]}");
	}

	private MockHttpServletRequestBuilder receive(UUID poId, String json) {
		return authed(post("/api/v1/purchase-orders/{poId}/receipts", poId))
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	/**
	 * The insert must fail, and it must fail on the CHECK rather than on anything else. Asserted on
	 * the constraint's own name: a NOT NULL violation or a typo'd column would also throw, and would
	 * have made a weaker version of this test pass while proving nothing about the constraint.
	 */
	private void assertRefusedByConstraint(Runnable insert, String what) {
		assertRefusedBy("po_lines_has_exactly_one_subject", insert, what);
	}

	/** The same, for any named constraint: the refusal must come from the one being tested. */
	private void assertRefusedBy(String constraint, Runnable write, String what) {
		try {
			write.run();
			throw new AssertionError(what + " should have been refused by the database");
		} catch (org.springframework.dao.DataIntegrityViolationException expected) {
			String message = String.valueOf(expected.getMostSpecificCause().getMessage());
			assert message.contains(constraint)
					: what + " was refused, but not by " + constraint + ": " + message;
		}
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
