package org.iskcon.kms.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The notification pipeline, minus the scheduler: sending through the cascade, consent suppression,
 * and the delivery webhook. The end-to-end enqueue-and-send path is {@code NotificationSendE2EIT}.
 */
@AutoConfigureMockMvc
class NotificationIT extends AbstractIntegrationTest {

	@Autowired
	private NotificationDispatcher dispatcher;

	@Autowired
	private NotificationService notificationService;

	@Autowired
	private MockMvc mvc;

	private static final String WHATSAPP_WEBHOOK_TOKEN = "wa-token-for-tests";
	private static final String WHATSAPP_APP_SECRET = "wa-app-secret-for-tests";

	@org.springframework.beans.factory.annotation.Autowired
	private org.iskcon.kms.tenancy.TenantSecretStore secrets;

	private JdbcTemplate admin;
	private UUID temple;
	private UUID consentedUser;
	private UUID unconsentedUser;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		temple = insertTenant();
		connectWhatsApp();
		consentedUser = insertUser("uid-consented", "consented@govinda.example", true);
		unconsentedUser = insertUser("uid-unconsented", "unconsented@govinda.example", false);
	}

	/**
	 * Enough of a WhatsApp connection for a callback to find this temple and be verified. Only the
	 * callback half: no phone number id, so nothing tries to send through Meta from a test.
	 */
	private void connectWhatsApp() {
		admin.update("""
				INSERT INTO tenant_settings (tenant_id, whatsapp_webhook_token)
				VALUES (?, ?) ON CONFLICT (tenant_id) DO UPDATE SET whatsapp_webhook_token = EXCLUDED.whatsapp_webhook_token
				""", temple, WHATSAPP_WEBHOOK_TOKEN);
		secrets.put(temple, org.iskcon.kms.tenancy.TenantSecretStore.Kind.WHATSAPP_APP_SECRET,
				WHATSAPP_APP_SECRET);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		secrets.deleteAll(temple);
		admin.execute("DELETE FROM tenant_settings");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a message sends on the preferred channel and records the attempt")
	void sendsOnPreferredChannel() {
		UUID id = insertPending(consentedUser, "+919876500020", "consented@govinda.example", "WHATSAPP");

		dispatchAs(temple, id);

		assertThat(statusOf(id)).isEqualTo("SENT");
		// This temple has connected no WhatsApp account, so that channel cannot send and the cascade
		// carries the message to SMS — the point of having a cascade at all.
		assertThat(finalChannelOf(id)).isEqualTo("SMS");
		// Two attempts, not one: WhatsApp is tried and cannot send, then SMS carries it.
		assertThat(attemptCount(id)).isEqualTo(2);
	}

	@Test
	@DisplayName("a raw send to a vendor phone (no account) works")
	void sendsToVendorPhone() {
		UUID id = insertPendingVendor("+919876500021", "WHATSAPP");

		dispatchAs(temple, id);

		assertThat(statusOf(id)).isEqualTo("SENT");
	}

	@Test
	@DisplayName("dispatching an already-sent message does nothing")
	void dispatchIsIdempotent() {
		UUID id = insertPending(consentedUser, "+919876500022", null, "WHATSAPP");
		admin.update("UPDATE notifications SET status = 'SENT', final_channel = 'WHATSAPP' WHERE id = ?", id);

		dispatchAs(temple, id);

		assertThat(attemptCount(id)).as("no new attempt was made").isZero();
	}

	@Test
	@DisplayName("a user who has not consented is suppressed, not sent to")
	void suppressesUnconsentedUser() {
		TenantContext.set(temple);
		try {
			UUID id = notificationService.notify(
					NotificationRecipient.user(unconsentedUser),
					NotificationTemplate.SHIFT_REMINDER,
					Map.of("role", "cook", "temple", "Govinda", "date", "Sunday", "time", "9am"),
					null);

			assertThat(statusOf(id)).isEqualTo("SUPPRESSED");
			assertThat(attemptCount(id)).isZero();
		} finally {
			TenantContext.clear();
		}
	}

	@Test
	@DisplayName("a signed delivery webhook marks the message delivered, and a duplicate is a no-op")
	void webhookMarksDeliveredIdempotently() throws Exception {
		UUID id = insertPending(consentedUser, "+919876500023", null, "WHATSAPP");
		admin.update("UPDATE notifications SET status = 'SENT', provider_message_id = 'wamid-1' WHERE id = ?", id);

		postWebhook(metaStatus("wamid-1", "delivered"), true)
				.andExpect(status().isOk());
		assertThat(statusOf(id)).isEqualTo("DELIVERED");

		// A retry of the same webhook must not move it anywhere else.
		postWebhook(metaStatus("wamid-1", "delivered"), true)
				.andExpect(status().isOk());
		assertThat(statusOf(id)).isEqualTo("DELIVERED");
	}

	@Test
	@DisplayName("a webhook with a bad signature is refused and changes nothing")
	void webhookRejectsBadSignature() throws Exception {
		UUID id = insertPending(consentedUser, "+919876500024", null, "WHATSAPP");
		admin.update("UPDATE notifications SET status = 'SENT', provider_message_id = 'wamid-2' WHERE id = ?", id);

		postWebhook(metaStatus("wamid-2", "delivered"), false)
				.andExpect(status().isForbidden());

		assertThat(statusOf(id)).as("an unsigned caller cannot change delivery state").isEqualTo("SENT");
	}

	// ---------------------------------------------------------------------

	private void dispatchAs(UUID tenantId, UUID notificationId) {
		TenantContext.set(tenantId);
		try {
			dispatcher.dispatch(notificationId);
		} finally {
			TenantContext.clear();
		}
	}

	/**
	 * A callback the way Meta actually sends one: an envelope of entries, each with changes, each
	 * carrying the statuses. The shape matters — a flat {@code {messageId, status}} was the dev
	 * contract, and a test written against it proved only that we could read our own invention.
	 */
	private static String metaStatus(String messageId, String status) {
		return """
				{"object":"whatsapp_business_account","entry":[{"id":"waba-1","changes":[{"field":"messages",
				 "value":{"messaging_product":"whatsapp","metadata":{"phone_number_id":"pn-1"},
				 "statuses":[{"id":"%s","status":"%s","recipient_id":"919876500023"}]}}]}]}
				""".formatted(messageId, status);
	}

	/** Addressed to this temple, and signed with this temple's own Meta app secret. */
	private org.springframework.test.web.servlet.ResultActions postWebhook(String body, boolean sign)
			throws Exception {
		String signature = sign ? "sha256=" + hmac(body, WHATSAPP_APP_SECRET) : "sha256=deadbeef";
		return mvc.perform(post("/api/v1/public/webhooks/whatsapp/" + WHATSAPP_WEBHOOK_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)
				.header("X-Hub-Signature-256", signature));
	}

	private static String hmac(String body, String secret) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
		StringBuilder hex = new StringBuilder();
		for (byte b : digest) {
			hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		}
		return hex.toString();
	}

	private String statusOf(UUID id) {
		return admin.queryForObject("SELECT status FROM notifications WHERE id = ?", String.class, id);
	}

	private String finalChannelOf(UUID id) {
		return admin.queryForObject("SELECT final_channel FROM notifications WHERE id = ?", String.class, id);
	}

	private int attemptCount(UUID id) {
		Integer c = admin.queryForObject(
				"SELECT count(*) FROM notification_attempts WHERE notification_id = ?", Integer.class, id);
		return c == null ? 0 : c;
	}

	private UUID insertPending(UUID userId, String phone, String email, String preferred) {
		return insert(userId, "Test Devotee", phone, email, preferred);
	}

	private UUID insertPendingVendor(String phone, String preferred) {
		return insert(null, "Vendor " + phone, phone, null, preferred);
	}

	private UUID insert(UUID userId, String label, String phone, String email, String preferred) {
		UUID id = UUID.randomUUID();
		admin.update("""
				INSERT INTO notifications (id, tenant_id, recipient_user_id, recipient_label,
						to_phone, to_email, template, preferred_channel, status)
				VALUES (?, ?, ?, ?, ?, ?, 'SHIFT_REMINDER', ?, 'PENDING')
				""", id, temple, userId, label, phone, email, preferred);
		return id;
	}

	private UUID insertTenant() {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
	}

	private UUID insertUser(String uid, String email, boolean consented) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status,
						preferred_channel, contact_consent_at, consent_version)
				VALUES (?, ?, 'Test Devotee', ?, '+919876500099', 'VOLUNTEER', 'ACTIVE', 'WHATSAPP', ?, ?)
				RETURNING id
				""", UUID.class, temple, uid, email,
				consented ? java.sql.Timestamp.from(java.time.Instant.now()) : null,
				consented ? "2026-08-10" : null);
	}
}
