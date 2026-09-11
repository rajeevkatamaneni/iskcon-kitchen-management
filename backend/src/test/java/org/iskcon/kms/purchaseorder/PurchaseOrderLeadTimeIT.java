package org.iskcon.kms.purchaseorder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * The vendor's lead time as one promise, enforced everywhere it shows (T-137, D-25 and D-24a).
 *
 * <p>Rajeev dictated the principle rather than the mechanism: <em>"We can't forget the Golden Rule:
 * Hold others to the same standards you want to be held to."</em> A lead time is agreed at
 * onboarding and it is the vendor's own number — <em>"we are good with 1 day lead time BUT we want
 * to be safe than sorry so we need 3 days notice to guarantee that everything will be delivered on
 * time 100%"</em> — and it binds both sides: we may hold a supplier to a delivery only where we gave
 * them the notice they asked for.
 *
 * <p>What these tests guard, in the order the facts happen:
 *
 * <ul>
 *   <li>the three zones of D-25, on Rajeev's own worked example: comfortably inside, the last day
 *       that works, and past it;</li>
 *   <li>that past the cutoff is a <em>refusal somebody can override</em> and never a block —
 *       <em>"That is a FAVOR we are asking"</em>;</li>
 *   <li>that the longest lead time on a multi-line order governs it;</li>
 *   <li>that a vendor with no recorded lead time produces silence, not an assumption;</li>
 *   <li><strong>that the figure is stamped on the order when it is sent, so editing the vendor's
 *       profile afterwards cannot re-judge it</strong> — <em>"No retroactive change here"</em>;
 *   <li>that an order we sent late is left out of that supplier's on-time figure and counted where
 *       a reader can see it; and</li>
 *   <li>that the nightly sweep cancels a draft nobody sent past its needed-by date, and never
 *       touches a part-delivered order (D-26).</li>
 * </ul>
 *
 * <p>One class rather than three, deliberately: every {@code @Import} is part of the Spring context
 * cache key, and a suite that spawns a fresh context per small test class is how CI comes to die of
 * heap with nothing failing.
 */
@AutoConfigureMockMvc
@Import(PurchaseOrderLeadTimeIT.StubVerifierConfiguration.class)
class PurchaseOrderLeadTimeIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** The temple's own day, which is the only day any of this is measured in. */
	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private PurchaseOrderAutoCancelRunner autoCancel;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID staffId;
	private UUID curd;
	private UUID ghee;
	private UUID dairy;
	private LocalDate today;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		today = LocalDate.now(TEMPLE_ZONE);
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		staffId = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-staff-a', 'Staff A', 'staff-a@example.com', '+919876500081',
					'KITCHEN_STAFF', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);
		curd = ingredient("Curd");
		ghee = ingredient("Ghee");
		// Heritage Fresh Dairy, and two days, are Rajeev's own worked example of 2026-09-10.
		dairy = vendor("Heritage Fresh Dairy");
		supplies(dairy, curd, 2);
		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM goods_receipt_lines");
		admin.execute("DELETE FROM goods_receipts");
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

	// ---- The three zones, on Rajeev's own example ------------------------

	@Test
	@DisplayName("comfortably inside the vendor's notice: no alarm bells, and the order goes")
	void insideTheLeadTimeIsSilent() throws Exception {
		// Curd wanted on the 15th from a dairy that asked for two days: ordering on the 11th is
		// "with in spec and no alarm bells here".
		String id = order(dairy, curd, today.plusDays(4));

		JsonNode before = detail(id).get("order");
		assert before.get("leadTimeDays").asInt() == 2 : "the vendor's own number, read live on a draft";
		assert before.get("orderBy").asText().equals(today.plusDays(2).toString())
				: "needed-by minus the lead time, and nothing else";
		assert before.get("orderUrgency").asText().equals("IN_TIME") : before.toString();

		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id)))
				.andExpect(status().isNoContent());

		assert stampedLeadTime(id) == 2 : "the figure that applied is stamped whether or not it was late";
		assert !sentLate(id) : "inside the promise";
		assert trail(id, "SENT").equals(poNumber(id) + " sent to vendor")
				: "nothing extra said about a perfectly ordinary send: " + trail(id, "SENT");
	}

	@Test
	@DisplayName("the last day that works is a nudge, never a refusal")
	void theLastDayIsANudge() throws Exception {
		// The 13th, for curd wanted on the 15th: still inside, and the screen says so — but the
		// order goes through untouched. "All of this nudging and coaching is optional."
		String id = order(dairy, curd, today.plusDays(2));

		assert detail(id).get("order").get("orderUrgency").asText().equals("ORDER_TODAY");

		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id)))
				.andExpect(status().isNoContent());
		assert !sentLate(id) : "the last day that works is not a day that does not";
	}

	@Test
	@DisplayName("past the cutoff is refused, and sent on the second press with what it costs said first")
	void pastTheCutoffIsRefusedUntilSomebodyMeansIt() throws Exception {
		// The 14th, for curd wanted on the 15th, from a dairy that asked for two days.
		String id = order(dairy, curd, today.plusDays(1));

		assert detail(id).get("order").get("orderUrgency").asText().equals("TOO_LATE");

		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400148"))
				// The words are the ones Rajeev's ruling asked for: what is happening, and that a
				// late delivery on this order will not count against them.
				.andExpect(jsonPath("$.message")
						.value("This order is going out later than the vendor asked to be given."));
		assert statusOf(id).equals("DRAFT") : "a refusal must not half-send the order";

		// The second press: the person has been told what it costs and means it.
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sendAnyway\":true}"))
				.andExpect(status().isNoContent());

		assert statusOf(id).equals("SENT");
		assert sentLate(id) : "the order went out after the last day it could have been filled";
		assert stampedLeadTime(id) == 2;
		String line = trail(id, "SENT");
		assert line.contains("after the last day it could be ordered") : line;
		assert line.contains("Heritage Fresh Dairy asked for 2 day(s) notice") : line;
		assert line.contains("not counted against them") : line;
	}

	@Test
	@DisplayName("the longest lead time on an order governs it — the slowest item is the deliverable date")
	void theLongestLeadTimeGoverns() throws Exception {
		// Curd next morning, ghee in four days. Rajeev, asked directly: the longest governs, because
		// the order is only fully deliverable when its slowest item is.
		supplies(dairy, curd, 1);
		supplies(dairy, ghee, 4);
		String id = orderOfTwo(dairy, curd, ghee, today.plusDays(3));

		JsonNode order = detail(id).get("order");
		assert order.get("leadTimeDays").asInt() == 4 : "the curd's single day must not govern";
		assert order.get("orderBy").asText().equals(today.minusDays(1).toString());
		assert order.get("orderUrgency").asText().equals("TOO_LATE");

		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400148"));
	}

	@Test
	@DisplayName("a vendor who has never given a lead time gets silence: no warning, no stamp, nothing held")
	void noRecordedLeadTimeIsSilence() throws Exception {
		UUID shop = vendor("The Corner Shop");
		supplies(shop, curd, null);
		// Wanted tomorrow, which would be far too late for anybody who had actually asked for notice.
		String id = order(shop, curd, today.plusDays(1));

		JsonNode order = detail(id).get("order");
		assert order.get("leadTimeDays").isNull() : "unknown is not two days here, and never zero";
		assert order.get("orderBy").isNull();
		assert order.get("orderUrgency").isNull();

		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id)))
				.andExpect(status().isNoContent());
		assert !sentLate(id) : "an order nobody can be late for cannot be sent late";
		assert admin.queryForObject(
				"SELECT lead_time_days FROM purchase_orders WHERE id = ?::uuid", Integer.class, id) == null;
	}

	@Test
	@DisplayName("an order with no needed-by date has no cutoff either")
	void noNeededByIsSilenceToo() throws Exception {
		String id = order(dairy, curd, null);

		JsonNode order = detail(id).get("order");
		assert order.get("orderBy").isNull() : "there is no day to count back from";
		assert order.get("orderUrgency").isNull();

		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id)))
				.andExpect(status().isNoContent());
		assert !sentLate(id);
		// The lead time is still stamped: it is the figure that applied, and a reader asking why
		// this order was judged the way it was needs it.
		assert stampedLeadTime(id) == 2;
	}

	// ---- The rule that shapes the schema ---------------------------------

	@Test
	@DisplayName("editing a vendor's lead time afterwards does not move a score already earned")
	void anSlaChangeIsNeverRetroactive() throws Exception {
		// An order sent inside the promise, delivered on the day, and now in the past so the
		// scorecard has something to say about it.
		String id = order(dairy, curd, today.plusDays(4));
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id)))
				.andExpect(status().isNoContent());
		backdate(id, today.minusDays(20), today.minusDays(10));
		deliveredInFull(id, today.minusDays(11));

		mvc.perform(authed(get("/api/v1/vendor-performance")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(100))
				.andExpect(jsonPath("$.vendors[0].ordersJudged").value(1))
				.andExpect(jsonPath("$.vendors[0].ordersSentLate").value(0));

		// The dairy rings up: from now on they want a month's notice. Every order placed from here
		// on is measured against thirty days — and nothing already sent moves an inch.
		admin.update("UPDATE vendor_supplies SET lead_time_days = 30 WHERE vendor_id = ?", dairy);

		mvc.perform(authed(get("/api/v1/vendor-performance")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.vendors[0].onTimePercent").value(100))
				.andExpect(jsonPath("$.vendors[0].ordersJudged").value(1))
				.andExpect(jsonPath("$.vendors[0].ordersSentLate").value(0));

		// And the order itself still says what it went out under, not what the profile says today.
		assert detail(id).get("order").get("leadTimeDays").asInt() == 2
				: "the stamp is what a sent order is read by";
	}

	@Test
	@DisplayName("an order we sent late is left out of the vendor's on-time figure, and counted where it shows")
	void anOrderWeSentLateIsNotTheirs() throws Exception {
		String id = order(dairy, curd, today.plusDays(1));
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sendAnyway\":true}"))
				.andExpect(status().isNoContent());
		// Backdated so the report would otherwise have something to say: the day it was wanted has
		// gone and nothing was ever delivered, which is a 0% on any ordinary order.
		backdate(id, today.minusDays(20), today.minusDays(10));

		mvc.perform(authed(get("/api/v1/vendor-performance")))
				.andExpect(status().isOk())
				// Placed with them, and counted as placed — we did order it.
				.andExpect(jsonPath("$.vendors[0].ordersPlaced").value(1))
				// And judged nowhere: we asked for something their notice period could not deliver.
				.andExpect(jsonPath("$.vendors[0].ordersJudged").value(0))
				.andExpect(jsonPath("$.vendors[0].onTimePercent").doesNotExist())
				// Visible, which is the half of the ruling that makes the number checkable.
				.andExpect(jsonPath("$.vendors[0].ordersSentLate").value(1))
				.andExpect(jsonPath("$.ordersSentLate").value(1));
	}

	@Test
	@DisplayName("an order we sent too late cannot be cancelled as one the vendor never delivered")
	void aLateSentOrderCannotBlameTheVendor() throws Exception {
		// D-25, extending T-129's ruling of the same day. T-129 refused the tick on an order nobody
		// sent — nothing was asked of the vendor. This is the same idea one step on: something WAS
		// asked of them, but after the notice they were promised, so what did not arrive is our
		// doing. Rajeev: "BECAUSE it is their fault NOT the vendors" — and here the fault is ours.
		//
		// Asserted against the endpoint and not only against the cancel form, because the form
		// hiding the box is not what stops this: the same endpoint takes the same field from
		// anything that can post to it. That is the reason PO_NEVER_SENT_TO_VENDOR exists in the
		// shape it does, written three lines above this rule's own code.
		String id = order(dairy, curd, today.plusDays(1));
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sendAnyway\":true}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/cancel", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"never answered the phone\",\"vendorAbandoned\":true}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400149"))
				.andExpect(jsonPath("$.message").value("This order went out after the notice this "
						+ "vendor asked for, so it can't be marked as one they failed to deliver."));

		// Refused, not half-applied: the order is still live and no claim was written.
		assert statusOf(id).equals("SENT") : "a refusal must not cancel the order anyway";
		assert !Boolean.TRUE.equals(admin.queryForObject(
				"SELECT vendor_abandoned FROM purchase_orders WHERE id = ?::uuid", Boolean.class, id));

		// And the cancellation itself is still offered — it is the CLAIM that is refused, never the
		// act. A temple must always be able to call off an order it no longer wants.
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/cancel", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"never answered the phone\"}"))
				.andExpect(status().isNoContent());
		assert statusOf(id).equals("CANCELLED");
	}

	@Test
	@DisplayName("an order we sent in time can still be cancelled as one the vendor never delivered")
	void anOrderSentInTimeCanStillBlameTheVendor() throws Exception {
		// The other side of the same rule, and the reason it needs its own test: a guard that
		// refused every no-show would pass the test above while destroying T-124.
		String id = order(dairy, curd, today.plusDays(4));
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id)))
				.andExpect(status().isNoContent());

		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/cancel", id))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"never answered the phone\",\"vendorAbandoned\":true}"))
				.andExpect(status().isNoContent());
		assert Boolean.TRUE.equals(admin.queryForObject(
				"SELECT vendor_abandoned FROM purchase_orders WHERE id = ?::uuid", Boolean.class, id));
	}

	// ---- The draft that nobody sends (D-24a) -----------------------------

	@Test
	@DisplayName("a draft past the day it was needed is cancelled by the sweep, and says so on its own trail")
	void aDraftNobodySentIsSweptAway() throws Exception {
		String id = order(dairy, curd, today.plusDays(4));
		backdate(id, today.minusDays(9), today.minusDays(1));

		assert autoCancel.sweep() == 1;

		JsonNode order = detail(id).get("order");
		assert order.get("status").asText().equals("CANCELLED");
		assert order.get("autoCancelled").asBoolean() : "the screens must be able to say who did it";
		assert order.get("cancelReason").asText().equals("Past need by date") : "Rajeev's own words";
		assert !order.get("vendorAbandoned").asBoolean()
				: "nothing was sent, so nothing is held against the vendor";

		// The act has to read as legibly afterwards as a person's cancellation would — and the one
		// thing that says a machine did it is the absence of an actor beside the line.
		String actor = admin.queryForObject("""
				SELECT actor_name FROM po_events WHERE po_id = ?::uuid AND event_type = 'AUTO_CANCELLED'
				""", String.class, id);
		assert actor == null : "a job has no name to sign with, and inventing one would be worse";
		assert trail(id, "AUTO_CANCELLED").contains("cancelled automatically") : trail(id, "AUTO_CANCELLED");

		// Idempotent, which every job in this application is required to be: a second run finds
		// nothing, because the order it swept is no longer a draft.
		assert autoCancel.sweep() == 0;
	}

	@Test
	@DisplayName("the sweep never touches a part-delivered order, however old — that needs a person")
	void theSweepLeavesAPartDeliveredOrderAlone() throws Exception {
		// D-26, agreed explicitly: "An order with 300 kg of real rice against it and a vendor
		// relationship behind it needs the human decision."
		String id = order(dairy, curd, today.plusDays(4));
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", id)))
				.andExpect(status().isNoContent());
		backdate(id, today.minusDays(30), today.minusDays(20));
		admin.update("UPDATE purchase_orders SET status = 'PARTIALLY_RECEIVED' WHERE id = ?::uuid", id);

		assert autoCancel.sweep() == 0 : "a sweep must not decide this";
		assert statusOf(id).equals("PARTIALLY_RECEIVED");

		// Nor a sent order still outstanding, nor one already received: only a draft.
		String sent = order(dairy, curd, today.plusDays(4));
		mvc.perform(authed(post("/api/v1/purchase-orders/{id}/send", sent)))
				.andExpect(status().isNoContent());
		backdate(sent, today.minusDays(30), today.minusDays(20));
		assert autoCancel.sweep() == 0;
		assert statusOf(sent).equals("SENT");
	}

	@Test
	@DisplayName("a draft still inside its needed-by date is left alone")
	void aDraftWithTimeLeftIsNotSwept() throws Exception {
		String id = order(dairy, curd, today);
		assert autoCancel.sweep() == 0 : "today is not past today";
		assert statusOf(id).equals("DRAFT");

		String noDate = order(dairy, curd, null);
		assert autoCancel.sweep() == 0 : "no date to have passed";
		assert statusOf(noDate).equals("DRAFT");
	}

	// ---------------------------------------------------------------------

	/** One line, one vendor, one date — through the endpoint, because the stamp is the API's job. */
	private String order(UUID vendorId, UUID ingredientId, LocalDate neededBy) throws Exception {
		String body = "{\"vendorId\":\"" + vendorId + "\","
				+ "\"neededBy\":" + (neededBy == null ? "null" : "\"" + neededBy + "\"") + ","
				+ "\"lines\":[{\"ingredientId\":\"" + ingredientId + "\",\"quantity\":6,\"unit\":\"KG\"}]}";
		return created(body);
	}

	private String orderOfTwo(UUID vendorId, UUID first, UUID second, LocalDate neededBy) throws Exception {
		String body = "{\"vendorId\":\"" + vendorId + "\",\"neededBy\":\"" + neededBy + "\","
				+ "\"lines\":[{\"ingredientId\":\"" + first + "\",\"quantity\":6,\"unit\":\"KG\"},"
				+ "{\"ingredientId\":\"" + second + "\",\"quantity\":2,\"unit\":\"KG\"}]}";
		return created(body);
	}

	private String created(String body) throws Exception {
		String response = mvc.perform(authed(post("/api/v1/purchase-orders"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(response).get("id").asText();
	}

	/**
	 * Moves an order into the past, straight to the columns.
	 *
	 * <p>Direct rather than through the application, and it has to be: an order can only be raised
	 * with today's order date and a needed-by date on or after it (KMS-400014), which leaves nothing
	 * for the scorecard to judge and no draft old enough for the sweep. What is being tested here is
	 * how stored facts are read, not the lifecycle that produced them — the same reasoning
	 * {@code VendorPerformanceIT}'s fixtures are written under.
	 */
	private void backdate(String id, LocalDate orderDate, LocalDate neededBy) {
		admin.update("""
				UPDATE purchase_orders
				SET order_date = ?, needed_by = ?,
					sent_at = CASE WHEN sent_at IS NULL THEN NULL ELSE ?::timestamptz END
				WHERE id = ?::uuid
				""", orderDate, neededBy,
				orderDate.plusDays(1).atTime(9, 0).atZone(TEMPLE_ZONE).toOffsetDateTime(), id);
	}

	/** Every line on the order, received in full on one day. */
	private void deliveredInFull(String id, LocalDate on) {
		UUID receipt = admin.queryForObject("""
				INSERT INTO goods_receipts (tenant_id, po_id, idempotency_key, received_by, received_at)
				VALUES (?, ?::uuid, ?, ?, ?) RETURNING id
				""", UUID.class, tenant, id, UUID.randomUUID().toString(), staffId,
				on.atTime(12, 0).atZone(TEMPLE_ZONE).toOffsetDateTime());
		admin.query("""
				SELECT id, ingredient_id, quantity, unit FROM purchase_order_lines WHERE po_id = ?::uuid
				""", rs -> {
			admin.update("""
					INSERT INTO goods_receipt_lines (
						tenant_id, receipt_id, po_line_id, ingredient_id, received_qty, rejected_qty, unit)
					VALUES (?, ?, ?, ?, ?, 0, ?)
					""", tenant, receipt, rs.getObject("id", UUID.class),
					rs.getObject("ingredient_id", UUID.class), rs.getBigDecimal("quantity"),
					rs.getString("unit"));
		}, id);
	}

	private JsonNode detail(String id) throws Exception {
		return JSON.readTree(mvc.perform(authed(get("/api/v1/purchase-orders/{id}", id)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
	}

	private String statusOf(String id) {
		return admin.queryForObject(
				"SELECT status FROM purchase_orders WHERE id = ?::uuid", String.class, id);
	}

	private String poNumber(String id) {
		return admin.queryForObject(
				"SELECT po_number FROM purchase_orders WHERE id = ?::uuid", String.class, id);
	}

	private boolean sentLate(String id) {
		return Boolean.TRUE.equals(admin.queryForObject(
				"SELECT sent_after_lead_time FROM purchase_orders WHERE id = ?::uuid", Boolean.class, id));
	}

	private int stampedLeadTime(String id) {
		Integer days = admin.queryForObject(
				"SELECT lead_time_days FROM purchase_orders WHERE id = ?::uuid", Integer.class, id);
		return days == null ? -1 : days;
	}

	private String trail(String id, String eventType) {
		return admin.queryForObject("""
				SELECT detail FROM po_events WHERE po_id = ?::uuid AND event_type = ?
				""", String.class, id, eventType);
	}

	private UUID ingredient(String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Dairy', 'KG') RETURNING id
				""", UUID.class, tenant, name);
	}

	private UUID vendor(String name) {
		return admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, ?, '+919812345678') RETURNING id
				""", UUID.class, tenant, name);
	}

	/**
	 * What this vendor said, at onboarding, about this thing. A null lead time is "nobody asked".
	 *
	 * <p>{@code preferred = false} on every row, which matters for a reason worth stating: only one
	 * vendor per ingredient may be preferred ({@code vendor_supplies_one_preferred}), and these
	 * tests deliberately give two vendors the same ingredient. It costs nothing, because the lead
	 * time governing an ORDER is read against the vendor the order is actually addressed to and
	 * never against whoever happens to be preferred — an order has no guessing left in it.
	 */
	private void supplies(UUID vendorId, UUID ingredientId, Integer leadTimeDays) {
		admin.update("""
				INSERT INTO vendor_supplies (tenant_id, vendor_id, ingredient_id, lead_time_days, preferred)
				VALUES (?, ?, ?, ?, false)
				ON CONFLICT (vendor_id, ingredient_id)
				DO UPDATE SET lead_time_days = EXCLUDED.lead_time_days
				""", tenant, vendorId, ingredientId, leadTimeDays);
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
