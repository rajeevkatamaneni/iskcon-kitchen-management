package org.iskcon.kms.donation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.payment.PaymentWebhookVerifier;
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
 * Recurring donations (E7-S3): a signed-in donor sets up a plan, each cycle's charge webhook records
 * a donation on it, cancellation and halting update status, a failed cycle notifies, and guests are
 * gated behind an account.
 */
@AutoConfigureMockMvc
@Import(RecurringDonationIT.StubVerifierConfiguration.class)
class RecurringDonationIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PaymentWebhookVerifier verifier;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID donor;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		donor = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-donor', 'Radha Devi', 'radha@example.com', '+919812345678', 'VOLUNTEER', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant);
		signIn("uid-donor");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM payment_events");
		admin.execute("DELETE FROM donations");
		admin.execute("DELETE FROM recurring_plans");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a donor sets up a plan; a charge webhook records a donation attached to it")
	void planAndCharge() throws Exception {
		Map<String, String> plan = createPlan();
		String subId = plan.get("subscriptionId");

		charged(subId, "pay_stub_c1", "evt-c1");

		Integer cycles = admin.queryForObject(
				"SELECT count(*) FROM donations WHERE recurring_plan_id = ?::uuid AND type = 'RECURRING' AND status = 'COMPLETED'",
				Integer.class, plan.get("id"));
		assert cycles == 1 : "one cycle donation recorded, was " + cycles;

		mvc.perform(authed(get("/api/v1/donations/recurring/{id}/history", plan.get("id"))))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].status").value("COMPLETED"));
	}

	@Test
	@DisplayName("a duplicate charge webhook does not double-record the cycle")
	void chargeIdempotent() throws Exception {
		Map<String, String> plan = createPlan();
		charged(plan.get("subscriptionId"), "pay_stub_c1", "evt-c1");
		charged(plan.get("subscriptionId"), "pay_stub_c1", "evt-c1"); // replay

		Integer cycles = admin.queryForObject(
				"SELECT count(*) FROM donations WHERE recurring_plan_id = ?::uuid", Integer.class, plan.get("id"));
		assert cycles == 1;
	}

	@Test
	@DisplayName("cancelling stops the plan; a halted webhook records the halt and notifies")
	void cancelAndHalt() throws Exception {
		Map<String, String> a = createPlan();
		mvc.perform(authed(post("/api/v1/donations/recurring/{id}/cancel", a.get("id"))))
				.andExpect(status().isNoContent());
		assert planStatus(a.get("id")).equals("CANCELLED");

		Map<String, String> b = createPlan();
		subscriptionEvent("subscription.halted", b.get("subscriptionId"), "evt-h1");
		assert planStatus(b.get("id")).equals("HALTED");
		Integer failNotices = admin.queryForObject(
				"SELECT count(*) FROM notifications WHERE template = 'RECURRING_CHARGE_FAILED'", Integer.class);
		assert failNotices >= 1 : "a halted cycle should notify the donor";
	}

	@Test
	@DisplayName("recurring requires an account — a guest is refused")
	void guestRefused() throws Exception {
		mvc.perform(post("/api/v1/donations/recurring")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"frequency\":\"MONTHLY\",\"amountInr\":501,\"consent\":true}"))
				.andExpect(status().isUnauthorized());
	}

	// ---------------------------------------------------------------------

	private Map<String, String> createPlan() throws Exception {
		String body = mvc.perform(authed(post("/api/v1/donations/recurring"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"frequency\":\"MONTHLY\",\"amountInr\":501,\"phone\":\"+919812345678\",\"consent\":true}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var node = JSON.readTree(body);
		return Map.of("id", node.get("id").asText(), "subscriptionId", node.get("subscriptionId").asText());
	}

	private void charged(String subId, String paymentId, String eventId) throws Exception {
		byte[] payload = ("{\"event\":\"subscription.charged\",\"payload\":{"
				+ "\"subscription\":{\"entity\":{\"id\":\"" + subId + "\"}},"
				+ "\"payment\":{\"entity\":{\"id\":\"" + paymentId + "\",\"method\":\"upi\"}}}}")
				.getBytes(StandardCharsets.UTF_8);
		deliver(payload, eventId);
	}

	private void subscriptionEvent(String type, String subId, String eventId) throws Exception {
		byte[] payload = ("{\"event\":\"" + type + "\",\"payload\":{\"subscription\":{\"entity\":{\"id\":\""
				+ subId + "\"}}}}").getBytes(StandardCharsets.UTF_8);
		deliver(payload, eventId);
	}

	private void deliver(byte[] payload, String eventId) throws Exception {
		mvc.perform(post("/api/v1/public/webhooks/razorpay")
						.header("X-Razorpay-Signature", verifier.sign(payload))
						.header("X-Razorpay-Event-Id", eventId)
						.content(payload))
				.andExpect(status().isOk());
	}

	private String planStatus(String id) {
		return admin.queryForObject("SELECT status FROM recurring_plans WHERE id = ?::uuid", String.class, id);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
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
