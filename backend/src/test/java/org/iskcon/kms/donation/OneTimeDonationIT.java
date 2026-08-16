package org.iskcon.kms.donation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.payment.PaymentWebhookVerifier;
import org.iskcon.kms.tenancy.TenantContext;
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
 * One-time donation via the payment provider (E7-S2): public checkout creates a PENDING record, only
 * a signed webhook completes it (never the client), duplicate webhooks don't double-record, abandoned
 * PENDINGs expire, and reconciliation catches a local/remote mismatch.
 */
@AutoConfigureMockMvc
class OneTimeDonationIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PaymentWebhookVerifier verifier;

	@Autowired
	private MonetaryDonationService donationService;

	@Autowired
	private DonationReconciliationService reconciliationService;

	@MockBean
	private Scheduler scheduler;

	/**
	 * The platform default gateway, spied rather than mocked: order creation and the webhook path
	 * behave exactly as they always do, and only the question the expiry sweep asks the provider is
	 * answered per-test.
	 */
	@org.springframework.boot.test.mock.mockito.SpyBean
	private org.iskcon.kms.payment.StubPaymentGateway gateway;

	private JdbcTemplate admin;
	private UUID tenant;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM payment_events");
		admin.execute("DELETE FROM donations");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("checkout creates a PENDING record; a captured webhook completes it and thanks the donor")
	void endToEndDonation() throws Exception {
		String orderId = checkout("{\"amountInr\":501,\"anonymous\":false,\"name\":\"Radha Devi\","
				+ "\"phone\":\"+919812345678\",\"consent\":true}");
		assert donationStatus(orderId).equals("PENDING") : "must start PENDING";

		captured(orderId, "pay_stub_1", "upt-evt-1");

		var row = admin.queryForMap("SELECT status, provider_payment_id, payment_mode FROM donations WHERE provider_order_id = ?", orderId);
		assert "COMPLETED".equals(row.get("status")) : "captured webhook should complete it";
		assert "pay_stub_1".equals(row.get("provider_payment_id"));
		Integer thanks = admin.queryForObject(
				"SELECT count(*) FROM notifications WHERE template = 'DONATION_THANK_YOU'", Integer.class);
		assert thanks == 1 : "one thank-you queued";
	}

	@Test
	@DisplayName("a duplicate captured webhook does not double-record or double-thank")
	void duplicateWebhookIdempotent() throws Exception {
		String orderId = checkout("{\"amountInr\":501,\"anonymous\":false,\"name\":\"Radha\",\"phone\":\"+919812345678\",\"consent\":true}");
		captured(orderId, "pay_stub_1", "evt-same");
		captured(orderId, "pay_stub_1", "evt-same"); // replayed event id

		Integer completed = admin.queryForObject(
				"SELECT count(*) FROM donations WHERE provider_order_id = ? AND status = 'COMPLETED'", Integer.class, orderId);
		assert completed == 1;
		Integer thanks = admin.queryForObject(
				"SELECT count(*) FROM notifications WHERE template = 'DONATION_THANK_YOU'", Integer.class);
		assert thanks == 1 : "a replayed webhook must not thank twice, was " + thanks;
	}

	@Test
	@DisplayName("a checkout with no webhook stays PENDING — the client can't mark it complete")
	void noWebhookStaysPending() throws Exception {
		String orderId = checkout("{\"amountInr\":1001,\"anonymous\":true,\"consent\":false}");
		assert donationStatus(orderId).equals("PENDING") : "no webhook, no completion";
	}

	@Test
	@DisplayName("abandoned PENDING donations expire")
	void abandonedExpire() throws Exception {
		String orderId = checkout("{\"amountInr\":51,\"anonymous\":true,\"consent\":false}");
		admin.update("UPDATE donations SET expires_at = now() - interval '1 hour' WHERE provider_order_id = ?", orderId);
		within(() -> donationService.expirePendingForCurrentTenant());
		assert donationStatus(orderId).equals("EXPIRED");
	}

	@Test
	@DisplayName("a gift the donor really paid for is completed by the sweep, not written off")
	void paidButUnconfirmedIsRescued() throws Exception {
		// The webhook never arrived — most plainly, a temple that has not registered it yet. The
		// money is at the provider, so expiring on the clock alone would lose the gift entirely.
		String orderId = checkout("{\"amountInr\":2500,\"anonymous\":true,\"consent\":false}");
		admin.update("UPDATE donations SET expires_at = now() - interval '1 hour' WHERE provider_order_id = ?", orderId);
		org.mockito.Mockito.doReturn(java.util.Optional.of(
						new org.iskcon.kms.payment.PaymentGateway.CapturedPayment("pay_stub_rescued", "upi")))
				.when(gateway).findCapturedPayment(orderId);

		within(() -> donationService.expirePendingForCurrentTenant());

		assert donationStatus(orderId).equals("COMPLETED") : "a paid gift must not expire";
		assert admin.queryForObject(
				"SELECT provider_payment_id FROM donations WHERE provider_order_id = ?", String.class, orderId)
				.equals("pay_stub_rescued");
		assert admin.queryForObject(
				"SELECT payment_mode FROM donations WHERE provider_order_id = ?", String.class, orderId)
				.equals("upi");
	}

	@Test
	@DisplayName("a provider that cannot be reached leaves the donation pending rather than expiring it")
	void unreachableProviderLeavesItPending() throws Exception {
		// "I could not ask" is not "nothing was paid". The next sweep asks again.
		String orderId = checkout("{\"amountInr\":700,\"anonymous\":true,\"consent\":false}");
		admin.update("UPDATE donations SET expires_at = now() - interval '1 hour' WHERE provider_order_id = ?", orderId);
		org.mockito.Mockito.doThrow(new IllegalStateException("provider unreachable"))
				.when(gateway).findCapturedPayment(orderId);

		within(() -> donationService.expirePendingForCurrentTenant());

		assert donationStatus(orderId).equals("PENDING") : "an unanswerable question must not expire a gift";
	}

	@Test
	@DisplayName("reconciliation flags a completed donation the provider can't confirm")
	void reconciliationCatchesMismatch() throws Exception {
		// Two completed donations: one the stub recognises, one it doesn't.
		seedCompleted("pay_stub_ok");
		seedCompleted("pay_bogus_999");
		List<ReconciliationMismatch> mismatches =
				reconciliationService.reconcile(tenant, LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
		assert mismatches.size() == 1 : "only the unconfirmable one should flag, was " + mismatches.size();
		assert mismatches.get(0).providerPaymentId().equals("pay_bogus_999");
	}

	// ---------------------------------------------------------------------

	private String checkout(String json) throws Exception {
		String body = mvc.perform(post("/api/v1/public/t/{slug}/donations", "radha-govinda")
						.contentType("application/json").content(json))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(body).get("orderId").asText();
	}

	private void captured(String orderId, String paymentId, String eventId) throws Exception {
		byte[] payload = ("{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{"
				+ "\"id\":\"" + paymentId + "\",\"order_id\":\"" + orderId + "\",\"method\":\"upi\"}}}}")
				.getBytes(StandardCharsets.UTF_8);
		mvc.perform(post("/api/v1/public/webhooks/razorpay")
						.header("X-Razorpay-Signature", verifier.sign(payload))
						.header("X-Razorpay-Event-Id", eventId)
						.content(payload))
				.andExpect(status().isOk());
	}

	private void seedCompleted(String paymentId) {
		admin.update("""
				INSERT INTO donations (tenant_id, type, amount_inr, status, is_anonymous, provider,
					provider_payment_id, donated_on)
				VALUES (?, 'ONE_TIME', 501, 'COMPLETED', true, 'stub', ?, CURRENT_DATE)
				""", tenant, paymentId);
	}

	private String donationStatus(String orderId) {
		return admin.queryForObject("SELECT status FROM donations WHERE provider_order_id = ?", String.class, orderId);
	}

	private void within(Runnable action) {
		TenantContext.set(tenant);
		try {
			action.run();
		} finally {
			TenantContext.clear();
		}
	}
}
