package org.iskcon.kms.donation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.payment.PaymentWebhookVerifier;
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

/**
 * One-time donation via the payment provider (E7-S2): a signed-in devotee's checkout creates a
 * PENDING record, only a signed webhook completes it (never the client), duplicate webhooks don't
 * double-record, abandoned PENDINGs expire, and reconciliation catches a local/remote mismatch.
 *
 * <p>The checkout was an unauthenticated form until 2026-08-29 and its donor was whatever the form
 * said. It is now the account's own, which changes who opens a checkout and nothing at all about
 * what a webhook may do with one afterwards — the reason every webhook assertion below is untouched.
 */
@AutoConfigureMockMvc
@Import(OneTimeDonationIT.StubVerifierConfiguration.class)
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

	@Autowired
	private StubTokenVerifier stubVerifier;

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
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		// The donor. A VOLUNTEER holds no donation permission of any kind and may still give, which
		// is the whole of what the endpoint asks for. Their contact is what the thank-you is sent to.
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-devotee', 'Radha Devi', 'radha@example.com', '+919812345678', 'VOLUNTEER', 'ACTIVE')
				""", tenant);
		stubVerifier.accept("uid-devotee");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM payment_events");
		admin.execute("DELETE FROM donations");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("checkout creates a PENDING record; a captured webhook completes it and thanks the donor")
	void endToEndDonation() throws Exception {
		String orderId = checkout(501);
		assert donationStatus(orderId).equals("PENDING") : "must start PENDING";

		captured(orderId, "pay_stub_1", "upt-evt-1");

		var row = admin.queryForMap("SELECT status, provider_payment_id, payment_mode FROM donations WHERE provider_order_id = ?", orderId);
		assert "COMPLETED".equals(row.get("status")) : "captured webhook should complete it";
		assert "pay_stub_1".equals(row.get("provider_payment_id"));
		Integer thanks = admin.queryForObject(
				"SELECT count(*) FROM notifications WHERE template = 'DONATION_THANK_YOU'", Integer.class);
		assert thanks == 1 : "one thank-you queued";
	}

	/**
	 * The control for T-111, and the reason it is written this way.
	 *
	 * <p>A removal cannot prove itself the way a fix can. The usual negative control — take the
	 * change out, watch the test go red — is not available here: there is no fix to patch out, and
	 * inverting it would mean restoring five classes and a table to watch a 404 stop happening,
	 * which demonstrates nothing except that deleted code is deleted. Manufacturing something
	 * shaped like a control would be worse than saying so.
	 *
	 * <p>What is available is stronger, and it is the pair of statements a removal actually has to
	 * make. <strong>First:</strong> the recurring surface is gone from the running application — not
	 * merely uncalled, not merely unreachable from a screen, but absent from the request mapping, so
	 * a donor who kept the URL, or a client built against the old contract, is answered 404 rather
	 * than starting a charge nobody can stop. <strong>Second:</strong> the giving path that stayed
	 * is undamaged by the removal — which is what {@code endToEndDonation} above asserts, in this
	 * same class and this same context, and is why the two belong in one run.
	 *
	 * <p>Both endpoints below were {@code @PreAuthorize("isAuthenticated()")}, and the request is
	 * made signed-in on purpose: a 403 would be an authorisation answer and would leave open the
	 * question of whether the mapping still exists. 404 is the mapping's own answer.
	 */
	@Test
	@DisplayName("every recurring-donation endpoint is gone from the application — each answers 404")
	void theRecurringSurfaceIsGone() throws Exception {
		String authorised = "Bearer valid-token";

		// Creating a plan — the one that could start a charge.
		mvc.perform(post("/api/v1/donations/recurring").header("Authorization", authorised)
						.contentType("application/json")
						.content("{\"frequency\":\"MONTHLY\",\"amountInr\":501,\"consent\":true}"))
				.andExpect(status().isNotFound());

		// Listing them, and one plan's cycle history.
		mvc.perform(get("/api/v1/donations/recurring").header("Authorization", authorised))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/donations/recurring/{id}/history", UUID.randomUUID())
						.header("Authorization", authorised))
				.andExpect(status().isNotFound());

		// And cancelling — the endpoint that existed, worked, and had no screen to call it. That
		// gap is the whole reason the feature went to Phase 2 rather than being finished here.
		mvc.perform(post("/api/v1/donations/recurring/{id}/cancel", UUID.randomUUID())
						.header("Authorization", authorised))
				.andExpect(status().isNotFound());

		// The schema went with the code, and this asks the database rather than trusting the
		// migration file to have said what it meant. V114's DROP TABLE is deliberately written
		// WITHOUT CASCADE, so the fact that this context started at all is itself the check that no
		// foreign key besides donations_recurring_plan_fk pointed at recurring_plans — an unknown
		// dependant would have failed Flyway here rather than being quietly taken down with it.
		assert admin.queryForObject("""
				SELECT count(*) FROM information_schema.tables
				WHERE table_schema = 'public' AND table_name = 'recurring_plans'
				""", Integer.class) == 0 : "recurring_plans must be gone";
		assert admin.queryForObject("""
				SELECT count(*) FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'donations'
				  AND column_name = 'recurring_plan_id'
				""", Integer.class) == 0 : "donations.recurring_plan_id must be gone";

		// And the one thing V114 deliberately did NOT remove, asserted so that a later tidying pass
		// meets a failing test rather than a silent decision: 'RECURRING' is still a legal value of
		// donations.type, because a donation row is the temple's record of money it received and
		// narrowing that CHECK would mean rewriting or destroying one.
		assert admin.queryForObject("""
				SELECT pg_get_constraintdef(oid) FROM pg_constraint
				WHERE conrelid = 'donations'::regclass AND conname = 'donations_type_valid'
				""", String.class).contains("RECURRING")
				: "the tombstoned type value must survive the removal";

		// The accepted consequence, stated positively: one-time giving is untouched by all of that.
		// Same context, same gateway, same webhook path — a full checkout still completes.
		String orderId = checkout(501);
		captured(orderId, "pay_stub_control", "upt-evt-control");
		assert "COMPLETED".equals(admin.queryForObject(
				"SELECT status FROM donations WHERE provider_order_id = ?", String.class, orderId))
				: "one-time giving must still complete end to end after the removal";
	}

	@Test
	@DisplayName("a duplicate captured webhook does not double-record or double-thank")
	void duplicateWebhookIdempotent() throws Exception {
		String orderId = checkout(501);
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
		String orderId = checkout(1001);
		assert donationStatus(orderId).equals("PENDING") : "no webhook, no completion";
	}

	@Test
	@DisplayName("abandoned PENDING donations expire")
	void abandonedExpire() throws Exception {
		String orderId = checkout(51);
		admin.update("UPDATE donations SET expires_at = now() - interval '1 hour' WHERE provider_order_id = ?", orderId);
		within(() -> donationService.expirePendingForCurrentTenant());
		assert donationStatus(orderId).equals("EXPIRED");
	}

	@Test
	@DisplayName("a gift the donor really paid for is completed by the sweep, not written off")
	void paidButUnconfirmedIsRescued() throws Exception {
		// The webhook never arrived — most plainly, a temple that has not registered it yet. The
		// money is at the provider, so expiring on the clock alone would lose the gift entirely.
		String orderId = checkout(2500);
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
		String orderId = checkout(700);
		admin.update("UPDATE donations SET expires_at = now() - interval '1 hour' WHERE provider_order_id = ?", orderId);
		org.mockito.Mockito.doThrow(new IllegalStateException("provider unreachable"))
				.when(gateway).findCapturedPayment(orderId);

		within(() -> donationService.expirePendingForCurrentTenant());

		assert donationStatus(orderId).equals("PENDING") : "an unanswerable question must not expire a gift";
	}

	@Test
	@DisplayName("an order belonging to a gateway the temple no longer uses is expired, not asked about")
	void orderFromAnotherProviderIsNotAskedAbout() throws Exception {
		// A temple that has since connected a real gateway still holds orders the previous one
		// created. Asking the new provider about them fails every time — which left those donations
		// pending for ever, warning once an hour, resolving neither way. Found in production.
		String orderId = checkout(300);
		admin.update("""
				UPDATE donations SET expires_at = now() - interval '1 hour', provider = 'a-gateway-we-left'
				WHERE provider_order_id = ?
				""", orderId);
		org.mockito.Mockito.doThrow(new IllegalStateException("Razorpay has never heard of this order"))
				.when(gateway).findCapturedPayment(orderId);

		within(() -> donationService.expirePendingForCurrentTenant());

		assert donationStatus(orderId).equals("EXPIRED")
				: "an order the current gateway did not create cannot have been paid through it";
		// And it was never asked, so nothing threw and nothing was left pending.
		org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never()).findCapturedPayment(orderId);
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

	@Test
	@DisplayName("a struck gift is not reported as a mismatch, however unconfirmable it is")
	void reconciliationIgnoresAStruckGift() throws Exception {
		// A gift struck as wrongly recorded is, by definition, the row least likely to have a real
		// payment behind it — so it is the row the gateway is least able to confirm, and it used to
		// come back as a mismatch on every run with no way for an operator to clear it. The standing
		// unconfirmable gift beside it is what keeps this test from passing vacuously: the report has
		// to still be capable of naming something.
		seedStruck("pay_bogus_struck");
		seedCompleted("pay_bogus_standing");

		List<ReconciliationMismatch> mismatches =
				reconciliationService.reconcile(tenant, LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));

		List<String> flagged = mismatches.stream().map(ReconciliationMismatch::providerPaymentId).toList();
		assert !flagged.contains("pay_bogus_struck") : "a struck gift must never be reported, was " + flagged;
		assert flagged.equals(List.of("pay_bogus_standing"))
				: "only the gift that stands should flag, was " + flagged;
	}

	@Test
	@DisplayName("the gateway is never asked about a struck gift — the wasted call is half the defect")
	void reconciliationDoesNotAskTheGatewayAboutAStruckGift() throws Exception {
		// Not asking matters on its own account, separately from what the report says. The daily job
		// runs per tenant for ever, so a struck row left in the query is one live provider API call
		// per run, per struck gift, to produce an answer that is thrown away. Asserting the absence of
		// the call is only worth anything alongside the assertion that the standing gift *was* asked
		// about: without that, a reconcile that asked nobody anything would pass this test happily.
		seedStruck("pay_bogus_struck");
		seedCompleted("pay_bogus_standing");

		reconciliationService.reconcile(tenant, LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));

		org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never()).fetchPaymentStatus("pay_bogus_struck");
		org.mockito.Mockito.verify(gateway, org.mockito.Mockito.times(1)).fetchPaymentStatus("pay_bogus_standing");
	}

	// ---------------------------------------------------------------------

	/**
	 * A signed-in devotee opening a checkout for {@code amountInr} rupees, and the provider order it
	 * created. The request carries the amount and nothing else — no name, no contact, no consent
	 * box: the temple already holds all of that, because the donor is one of its own people.
	 */
	private String checkout(int amountInr) throws Exception {
		String body = mvc.perform(post("/api/v1/donations/one-time")
						.header("Authorization", "Bearer valid-token")
						.contentType("application/json").content("{\"amountInr\":" + amountInr + "}"))
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

	/**
	 * The same completed gift, then struck the way {@code DonationVoidService} strikes one: the mark
	 * is written and {@code status} is deliberately left at COMPLETED, because that is what V104 does
	 * and a seed that quietly set some other status would test a row the product cannot produce.
	 * V104's two CHECK constraints demand who and why alongside the timestamp, so all three go on.
	 */
	private void seedStruck(String paymentId) {
		seedCompleted(paymentId);
		admin.update("""
				UPDATE donations
				SET voided_at = now(),
					voided_by = (SELECT id FROM users WHERE firebase_uid = 'uid-devotee'),
					void_reason = 'entered twice against the wrong donor'
				WHERE provider_payment_id = ?
				""", paymentId);
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
			accepted.put("valid-token", new VerifiedSubject(uid, uid + "@example.com", "+919812345678"));
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
