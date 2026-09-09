package org.iskcon.kms.vendor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A vendor you walk into has no WhatsApp number (T-025, D-2).
 *
 * <p>The temple buys four brooms from the hardware shop on the corner, on a purchase order, and
 * files the bill. That shop is a vendor — {@code purchase_orders.vendor_id} is NOT NULL and the
 * invoice, the payment and the month's spend all hang off it — but it has no number, and does not
 * need one, because nobody is going to WhatsApp it a purchase order. V101 lets the row exist.
 *
 * <p>Everything below is one of the two places a null then leaked out as the four characters
 * "null", or one of the two rules that must survive the relaxation.
 *
 * <ul>
 *   <li><strong>The column relaxes; the format does not.</strong> Null is a vendor with no number.
 *       "98450" is a typo, and is still refused.
 *   <li><strong>The refusal moved to where the reason lives.</strong> Sending on WhatsApp asks for a
 *       number before it decides anything, so a phoneless vendor gets KMS-400130 — which says to
 *       hand the sheet over — and the draft is never moved to SENT on the way to failing.
 *   <li><strong>A described line names itself in the message.</strong> Since T-024 a line may carry
 *       a description instead of an ingredient, and {@code Collectors.joining} would have put the
 *       literal "null" into a message a real supplier reads.
 *   <li><strong>A vendor with a number is completely unaffected.</strong>
 * </ul>
 *
 * <p>A mocked {@link Scheduler} keeps the enqueue path hermetic, exactly as
 * {@code PurchaseOrderWhatsAppIT} does: the notification is written and stays PENDING.
 */
@AutoConfigureMockMvc
@Import(VendorWithoutPhoneIT.StubVerifierConfiguration.class)
class VendorWithoutPhoneIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID staffId;
	private UUID rice;

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
				VALUES (?, 'uid-staff-a', 'Staff A', 'staff-a@example.com', '+919876500081',
					'KITCHEN_STAFF', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);
		rice = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Rice', 'Grains', 'KG') RETURNING id
				""", UUID.class, tenant);
		stubVerifier.accept("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM documents");
		admin.execute("DELETE FROM po_events");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM purchase_order_lines");
		admin.execute("DELETE FROM purchase_orders");
		admin.execute("DELETE FROM po_sequence");
		admin.execute("DELETE FROM vendor_status_changes");
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- The column, and what it still refuses ---------------------------

	@Test
	@DisplayName("a vendor saves with no phone at all, and stores a real NULL rather than a blank")
	void savesWithNoPhone() throws Exception {
		UUID id = create("{\"name\":\"Corner Hardware\"}");

		// A NULL, not "" — an empty string would satisfy "somebody typed nothing" while failing the
		// E.164 check the moment anybody edited the row, and would print as an empty phone number on
		// the vendor sheet.
		Integer nulls = admin.queryForObject(
				"SELECT count(*) FROM vendors WHERE id = ? AND phone IS NULL", Integer.class, id);
		assert nulls == 1 : "the vendor's phone should be NULL, not blank";

		// It comes back over the wire as an explicit null, not an absent key. `VendorView.phone` is
		// required-and-nullable in frontend/lib/api.ts precisely because a spread can silently drop an
		// optional property and no test can tell an omitted key from a null one — so the body is
		// inspected for the key itself rather than asked whether the path "exists", which is a
		// question JsonPath answers the same way for both.
		String json = mvc.perform(authed(get("/api/v1/vendors/{id}", id)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assert json.contains("\"phone\":null") : "phone should be present and null; got " + json;

		// And this is NOT whatsapp_reachable. That flag is cleared when a send FAILS — a number that
		// exists and bounced — and a vendor that never had a number reads true on it like any other
		// new row. Conflating the two would make the guard silently wrong.
		Boolean reachable = admin.queryForObject(
				"SELECT whatsapp_reachable FROM vendors WHERE id = ?", Boolean.class, id);
		assert Boolean.TRUE.equals(reachable)
				: "a phoneless vendor is not an unreachable one; whatsapp_reachable is a different fact";
	}

	@Test
	@DisplayName("a malformed phone is still refused — the check permits null, not rubbish")
	void malformedPhoneStillRefused() throws Exception {
		// KMS-400003 since T-021 — the phone is the only failure, so the specific message reaches
		// the reader. What this test is about is unchanged: the check permits null, not rubbish.
		mvc.perform(createRequest("{\"name\":\"Typo Traders\",\"phone\":\"98450\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400003"));

		// A blank box is not a number either. The screens send null rather than "", and if one ever
		// sends "" again it must be refused rather than quietly stored.
		mvc.perform(createRequest("{\"name\":\"Blank Traders\",\"phone\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400003"));

		Integer saved = admin.queryForObject(
				"SELECT count(*) FROM vendors WHERE name IN ('Typo Traders', 'Blank Traders')",
				Integer.class);
		assert saved == 0 : "neither refusal should have left a row behind";
	}

	@Test
	@DisplayName("a phoneless vendor can be edited, and both sides of the audit carry the null")
	void phonelessVendorCanBeEdited() throws Exception {
		UUID id = create("{\"name\":\"Corner Hardware\"}");

		// This is the Map.of trap. Map.of throws a NullPointerException on a null VALUE, and the
		// vendor audit snapshotted BOTH sides with it — so editing a vendor that has no number was a
		// 500 on the before-snapshot, quite apart from the .trim() on the request.
		mvc.perform(update(id, "{\"name\":\"Corner Hardware & Paints\"}"))
				.andExpect(status().isNoContent());

		// jsonb_exists rather than `?`, which JDBC would read as a bind placeholder. The key's
		// PRESENCE is asked separately from its VALUE on purpose: `before_state -> 'phone'` comes back
		// as Java null both when the key is absent and when it holds a JSON null, so a single
		// comparison would pass for a trail that recorded nothing at all about the number.
		Map<String, Object> event = admin.queryForMap("""
				SELECT before_state ->> 'name' AS before_name,
					   after_state  ->> 'name' AS after_name,
					   jsonb_exists(before_state, 'phone') AS before_has_phone,
					   jsonb_exists(after_state,  'phone') AS after_has_phone,
					   before_state -> 'phone' = 'null'::jsonb AS before_phone_null,
					   after_state  -> 'phone' = 'null'::jsonb AS after_phone_null
				FROM audit_events WHERE action = 'VENDOR_UPDATED' AND entity_id = ?
				""", id);
		assert "Corner Hardware".equals(event.get("before_name")) : event.toString();
		assert "Corner Hardware & Paints".equals(event.get("after_name")) : event.toString();
		// A JSON null, present and explicit, on both sides — the trail should say the vendor had no
		// number and still has none, not drop the key and leave a reader guessing.
		assert Boolean.TRUE.equals(event.get("before_has_phone")) : event.toString();
		assert Boolean.TRUE.equals(event.get("after_has_phone")) : event.toString();
		assert Boolean.TRUE.equals(event.get("before_phone_null")) : event.toString();
		assert Boolean.TRUE.equals(event.get("after_phone_null")) : event.toString();

		Integer stillNull = admin.queryForObject(
				"SELECT count(*) FROM vendors WHERE id = ? AND phone IS NULL", Integer.class, id);
		assert stillNull == 1 : "an edit that says nothing about the phone must not invent one";
	}

	@Test
	@DisplayName("a number can be added to a walk-in vendor later, and the audit records the change")
	void aNumberCanBeAddedLater() throws Exception {
		UUID id = create("{\"name\":\"Corner Hardware\"}");
		mvc.perform(update(id, "{\"name\":\"Corner Hardware\",\"phone\":\"+919845012399\"}"))
				.andExpect(status().isNoContent());

		String phone = admin.queryForObject(
				"SELECT phone FROM vendors WHERE id = ?", String.class, id);
		assert "+919845012399".equals(phone) : "got " + phone;

		// The after-snapshot is built from the stored row, not from the request, so it says what the
		// temple's data became rather than agreeing with the caller by construction.
		String auditedAfter = admin.queryForObject("""
				SELECT after_state ->> 'phone' FROM audit_events
				WHERE action = 'VENDOR_UPDATED' AND entity_id = ?
				""", String.class, id);
		assert "+919845012399".equals(auditedAfter) : "got " + auditedAfter;
	}

	// ---- The send path ---------------------------------------------------

	@Test
	@DisplayName("sending to a phoneless vendor is KMS-400130 and leaves the order in DRAFT")
	void sendingToAPhonelessVendorRefusesAndLeavesTheDraftAlone() throws Exception {
		UUID walkIn = vendorRow("Corner Hardware", null);
		UUID poId = draftPo("PO-2026-0060", walkIn);
		ingredientLine(poId);

		mvc.perform(whatsapp(poId))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400130"));

		// The half a 409 alone would not prove. The refusal used to happen inside the notification
		// service, AFTER purchaseOrders.send() had already moved this order DRAFT -> SENT; the
		// transaction rolled that back, so the data was never wrong, but the check now genuinely
		// precedes the transition rather than relying on a rollback to undo it.
		String status = admin.queryForObject(
				"SELECT status FROM purchase_orders WHERE id = ?", String.class, poId);
		assert "DRAFT".equals(status) : "the order must still be a draft; got " + status;
		Integer sentEvents = admin.queryForObject(
				"SELECT count(*) FROM po_events WHERE po_id = ?", Integer.class, poId);
		assert sentEvents == 0 : "nothing should have been recorded on the order's trail";

		// And nothing was written for a recipient nobody can name. The old code built the label by
		// concatenation — "Vendor " + null — before it discovered there was no address to send to.
		assertNoVendorNullNotification();
		Integer notifications = admin.queryForObject(
				"SELECT count(*) FROM notifications", Integer.class);
		assert notifications == 0 : "a refused send queues nothing at all; got " + notifications;
	}

	@Test
	@DisplayName("a described line names itself in the message, and 'null' appears nowhere in it")
	void aDescribedLineNamesItselfInTheMessage() throws Exception {
		// PO-2026-0030 on staging is this exact shape: a real order carrying a described line, whose
		// WhatsApp summary would have read "2 item(s): Rice, null" before this fix.
		UUID wholesale = vendorRow("Govind Wholesale", "+919845012303");
		UUID poId = draftPo("PO-2026-0061", wholesale);
		ingredientLine(poId);
		describedLine(poId, "Plastic stool");

		mvc.perform(whatsapp(poId)).andExpect(status().isAccepted());

		String summary = admin.queryForObject(
				"SELECT params ->> 'summary' FROM notifications", String.class);
		assert summary != null : "the send should have queued a notification";
		assert summary.contains("Plastic stool")
				: "the described line must say what it is; got " + summary;
		assert summary.contains("Rice") : "the ingredient line is still named; got " + summary;
		// The whole point. Collectors.joining appends a null element as four literal characters, and
		// javac has nothing to say about it — this is the assertion that would have caught it.
		assert !summary.contains("null")
				: "no part of a message a supplier reads may be the word null; got " + summary;

		// Nothing else in the message body either — the vendor's name, the PO number and the dates
		// all travel as parameters of the same JSON.
		String params = admin.queryForObject("SELECT params::text FROM notifications", String.class);
		assert params != null && !params.contains("null")
				: "the message parameters carry no nulls; got " + params;
	}

	@Test
	@DisplayName("a vendor with a phone is completely unaffected: the order sends and goes to SENT")
	void aVendorWithAPhoneIsUnaffected() throws Exception {
		UUID wholesale = vendorRow("Govind Wholesale", "+919845012303");
		UUID poId = draftPo("PO-2026-0062", wholesale);
		ingredientLine(poId);

		mvc.perform(whatsapp(poId)).andExpect(status().isAccepted());

		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", poId)))
				.andExpect(jsonPath("$.order.status").value("SENT"));

		Map<String, Object> notification = admin.queryForMap(
				"SELECT recipient_label, to_phone, status FROM notifications");
		assert "Vendor +919845012303".equals(notification.get("recipient_label")) : notification.toString();
		assert "+919845012303".equals(notification.get("to_phone")) : notification.toString();
		assertNoVendorNullNotification();
	}

	// ---------------------------------------------------------------------

	/**
	 * No notification was ever written for a vendor nobody can name.
	 *
	 * <p>Inspected rather than counted on a LIKE: the label is built by concatenation, so a null
	 * phone produced the exact string "Vendor null" and nothing else would.
	 */
	private void assertNoVendorNullNotification() {
		List<String> labels = admin.queryForList(
				"SELECT recipient_label FROM notifications", String.class);
		assert !labels.contains("Vendor null")
				: "a notification was written for 'Vendor null'; got " + labels;
	}

	private UUID vendorRow(String name, String phone) {
		return admin.queryForObject("""
				INSERT INTO vendors (tenant_id, name, phone) VALUES (?, ?, ?) RETURNING id
				""", UUID.class, tenant, name, phone);
	}

	private UUID draftPo(String number, UUID vendorId) {
		return admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, created_by)
				VALUES (?, ?, ?, 'DRAFT', ?) RETURNING id
				""", UUID.class, tenant, number, vendorId, staffId);
	}

	private void ingredientLine(UUID poId) {
		admin.update("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit)
				VALUES (?, ?, ?, 30, 'KG')
				""", tenant, poId, rice);
	}

	private void describedLine(UUID poId, String description) {
		admin.update("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, description, quantity, unit)
				VALUES (?, ?, ?, 4, 'PIECES')
				""", tenant, poId, description);
	}

	private UUID create(String json) throws Exception {
		String body = mvc.perform(createRequest(json)).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
	}

	private MockHttpServletRequestBuilder createRequest(String json) {
		return authed(post("/api/v1/vendors")).contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder update(UUID vendorId, String json) {
		return authed(put("/api/v1/vendors/{id}", vendorId))
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder whatsapp(UUID poId) {
		return authed(post("/api/v1/purchase-orders/{id}/whatsapp", poId));
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
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
