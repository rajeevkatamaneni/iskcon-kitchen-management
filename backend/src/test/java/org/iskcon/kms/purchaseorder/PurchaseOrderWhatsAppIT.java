package org.iskcon.kms.purchaseorder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.notification.DeliveryStatus;
import org.iskcon.kms.notification.NotificationDeliveryService;
import org.iskcon.kms.tenancy.TenantContext;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * WhatsApp PO delivery (E5-S7): sending through the notification service records a linked
 * WHATSAPP_SENT event and is audited; a delivery webhook reflects the outcome onto the PO trail and
 * flags an unreachable vendor; a rate guard blocks an immediate resend; a closed PO can't be sent.
 * A mocked {@link Scheduler} keeps the enqueue path hermetic (the notification stays PENDING).
 */
@AutoConfigureMockMvc
@Import(PurchaseOrderWhatsAppIT.StubVerifierConfiguration.class)
class PurchaseOrderWhatsAppIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private NotificationDeliveryService deliveryService;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler;

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
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-vol-a', 'Vol A', 'vol-a@example.com', '+919876500082', 'VOLUNTEER', 'ACTIVE')
				""", tenant);
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
		TenantContext.clear();
		admin.execute("DELETE FROM documents");
		admin.execute("DELETE FROM po_events");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM purchase_order_lines");
		admin.execute("DELETE FROM purchase_orders");
		admin.execute("DELETE FROM po_sequence");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("sending a SENT PO records a linked WHATSAPP_SENT trail event and is audited")
	void sendRecordsTrailAndAudit() throws Exception {
		UUID poId = sentPo("PO-2026-0042");

		String body = mvc.perform(whatsapp(poId)).andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();
		UUID notificationId = UUID.fromString(
				new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("notificationId").asText());

		// A trail event, linked to the notification.
		UUID linkedPo = admin.queryForObject(
				"SELECT po_id FROM po_events WHERE notification_id = ? AND event_type = 'WHATSAPP_SENT'",
				UUID.class, notificationId);
		assert poId.equals(linkedPo);

		// Audited with the actor.
		Integer audits = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'PO_WHATSAPP_SENT' AND entity_id = ?",
				Integer.class, poId);
		assert audits == 1 : "send should be audited once";
	}

	@Test
	@DisplayName("sending a draft transitions it to SENT as part of delivering it")
	void sendingDraftTransitionsIt() throws Exception {
		UUID poId = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, created_by)
				VALUES (?, 'PO-2026-0043', ?, 'DRAFT', ?) RETURNING id
				""", UUID.class, tenant, vendor, staffId);
		line(poId);

		mvc.perform(whatsapp(poId)).andExpect(status().isAccepted());
		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", poId)))
				.andExpect(jsonPath("$.order.status").value("SENT"));
	}

	@Test
	@DisplayName("an immediate resend is rate-limited")
	void resendRateLimited() throws Exception {
		UUID poId = sentPo("PO-2026-0044");
		mvc.perform(whatsapp(poId)).andExpect(status().isAccepted());
		mvc.perform(whatsapp(poId))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4925"));
	}

	@Test
	@DisplayName("a cancelled PO cannot be sent to a vendor")
	void cancelledCannotBeSent() throws Exception {
		UUID poId = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, cancel_reason,
					cancelled_at, created_by)
				VALUES (?, 'PO-2026-0045', ?, 'CANCELLED', 'duplicate', now(), ?) RETURNING id
				""", UUID.class, tenant, vendor, staffId);
		line(poId);
		mvc.perform(whatsapp(poId))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4924"));
	}

	@Test
	@DisplayName("a delivered webhook lands on the PO trail")
	void webhookDeliveredLandsOnTrail() throws Exception {
		UUID poId = sentPo("PO-2026-0046");
		UUID notificationId = send(poId);
		setProviderMessageId(notificationId, "wamid.DELIVERED-1");

		deliveryService.applyStatus("wamid.DELIVERED-1", DeliveryStatus.DELIVERED);
		TenantContext.clear();

		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", poId)))
				.andExpect(jsonPath("$.events[?(@.eventType=='WHATSAPP_DELIVERED')]").exists());
	}

	@Test
	@DisplayName("a failed webhook flags the vendor and surfaces a failure on the trail")
	void webhookFailedFlagsVendor() throws Exception {
		UUID poId = sentPo("PO-2026-0047");
		UUID notificationId = send(poId);
		setProviderMessageId(notificationId, "wamid.FAILED-1");

		deliveryService.applyStatus("wamid.FAILED-1", DeliveryStatus.FAILED);
		TenantContext.clear();

		mvc.perform(authed(get("/api/v1/purchase-orders/{id}", poId)))
				.andExpect(jsonPath("$.events[?(@.eventType=='WHATSAPP_FAILED')]").exists());
		Boolean reachable = admin.queryForObject(
				"SELECT whatsapp_reachable FROM vendors WHERE id = ?", Boolean.class, vendor);
		assert Boolean.FALSE.equals(reachable) : "vendor should be flagged unreachable";
	}

	@Test
	@DisplayName("a volunteer cannot send POs on WhatsApp")
	void volunteerForbidden() throws Exception {
		UUID poId = sentPo("PO-2026-0048");
		signIn("uid-vol-a");
		mvc.perform(whatsapp(poId)).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private UUID send(UUID poId) throws Exception {
		String body = mvc.perform(whatsapp(poId)).andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(
				new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("notificationId").asText());
	}

	private void setProviderMessageId(UUID notificationId, String providerMessageId) {
		admin.update("UPDATE notifications SET provider_message_id = ? WHERE id = ?",
				providerMessageId, notificationId);
	}

	private UUID sentPo(String number) {
		UUID poId = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, sent_at, created_by)
				VALUES (?, ?, ?, 'SENT', now(), ?) RETURNING id
				""", UUID.class, tenant, number, vendor, staffId);
		line(poId);
		return poId;
	}

	private void line(UUID poId) {
		admin.update("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit)
				VALUES (?, ?, ?, 30, 'KG')
				""", tenant, poId, rice);
	}

	private MockHttpServletRequestBuilder whatsapp(UUID poId) {
		return authed(post("/api/v1/purchase-orders/{id}/whatsapp", poId));
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
