package org.iskcon.kms.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.tenancy.TenantSecretStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A payment webhook addressed to one temple (E7-S9).
 *
 * <p>The token in the path identifies the temple before the body can be verified, which is forced:
 * the signature can only be checked with that temple's own secret. So the thing worth proving is
 * that the token buys nothing on its own — a correct address with a wrong signature is refused
 * exactly as an unknown address is, and one temple's secret cannot sign for another.
 */
@AutoConfigureMockMvc
class TenantPaymentWebhookIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private TenantSecretStore secrets;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID bengaluru;
	private UUID mysore;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		bengaluru = tenant("radha-govinda", "Bengaluru Temple");
		mysore = tenant("iskcon-mysore", "Mysore Temple");
		configure(bengaluru, "token-bengaluru", "bengaluru-webhook-secret");
		configure(mysore, "token-mysore", "mysore-webhook-secret");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		secrets.deleteAll(bengaluru);
		secrets.deleteAll(mysore);
		admin.execute("DELETE FROM payment_events");
		admin.execute("DELETE FROM tenant_settings");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a correctly signed webhook is accepted, and is the only thing that turns the light green")
	void aSignedWebhookIsAcceptedAndRecorded() throws Exception {
		assertThat(webhookSeenAt(bengaluru)).isNull();

		byte[] body = event("evt-1", "order_x");
		mvc.perform(post("/api/v1/public/webhooks/payments/{token}", "token-bengaluru")
						.header("X-Razorpay-Signature", sign(body, "bengaluru-webhook-secret"))
						.header("X-Razorpay-Event-Id", "evt-1")
						.content(body))
				.andExpect(status().isOk());

		assertThat(webhookSeenAt(bengaluru)).isNotNull();
	}

	@Test
	@DisplayName("the address alone buys nothing — a wrong signature is refused")
	void theTokenIsNotACredential() throws Exception {
		byte[] body = event("evt-2", "order_x");
		mvc.perform(post("/api/v1/public/webhooks/payments/{token}", "token-bengaluru")
						.header("X-Razorpay-Signature", "not-the-signature")
						.header("X-Razorpay-Event-Id", "evt-2")
						.content(body))
				.andExpect(status().isForbidden());

		assertThat(webhookSeenAt(bengaluru)).isNull();
	}

	@Test
	@DisplayName("one temple's secret cannot sign for another temple")
	void secretsDoNotCrossTheBoundary() throws Exception {
		byte[] body = event("evt-3", "order_x");

		// Correctly signed — but with Mysore's secret, sent to Bengaluru's address.
		mvc.perform(post("/api/v1/public/webhooks/payments/{token}", "token-bengaluru")
						.header("X-Razorpay-Signature", sign(body, "mysore-webhook-secret"))
						.header("X-Razorpay-Event-Id", "evt-3")
						.content(body))
				.andExpect(status().isForbidden());

		assertThat(webhookSeenAt(bengaluru)).isNull();
		assertThat(webhookSeenAt(mysore)).isNull();
	}

	@Test
	@DisplayName("an unknown address is refused without saying why")
	void anUnknownTokenIsRefused() throws Exception {
		byte[] body = event("evt-4", "order_x");
		mvc.perform(post("/api/v1/public/webhooks/payments/{token}", "token-that-never-existed")
						.header("X-Razorpay-Signature", sign(body, "bengaluru-webhook-secret"))
						.header("X-Razorpay-Event-Id", "evt-4")
						.content(body))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("a signed event we do not model is acknowledged, so the provider stops retrying")
	void anUnmodelledEventIsAcknowledged() throws Exception {
		byte[] body = ("{\"event\":\"payment.dispute.created\",\"payload\":{}}")
				.getBytes(StandardCharsets.UTF_8);
		mvc.perform(post("/api/v1/public/webhooks/payments/{token}", "token-bengaluru")
						.header("X-Razorpay-Signature", sign(body, "bengaluru-webhook-secret"))
						.header("X-Razorpay-Event-Id", "evt-5")
						.content(body))
				.andExpect(status().isOk());
	}

	// ---- helpers ----------------------------------------------------------

	private UUID tenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, name);
	}

	private void configure(UUID tenantId, String token, String webhookSecret) {
		admin.update("""
				INSERT INTO tenant_settings (tenant_id, payment_provider, payment_key_id, payment_webhook_token)
				VALUES (?, 'RAZORPAY', 'rzp_test_key', ?)
				""", tenantId, token);
		secrets.put(tenantId, TenantSecretStore.Kind.PAYMENT_WEBHOOK_SECRET, webhookSecret);
	}

	private Object webhookSeenAt(UUID tenantId) {
		return admin.queryForObject(
				"SELECT payment_webhook_seen_at FROM tenant_settings WHERE tenant_id = ?",
				Object.class, tenantId);
	}

	private static byte[] event(String eventId, String orderId) {
		return ("{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{"
				+ "\"id\":\"pay_" + eventId + "\",\"order_id\":\"" + orderId + "\",\"method\":\"upi\"}}}}")
				.getBytes(StandardCharsets.UTF_8);
	}

	/** What a provider holding this secret would send. */
	private static String sign(byte[] body, String secret) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		byte[] digest = mac.doFinal(body);
		StringBuilder hex = new StringBuilder(digest.length * 2);
		for (byte b : digest) {
			hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		}
		return hex.toString();
	}
}
