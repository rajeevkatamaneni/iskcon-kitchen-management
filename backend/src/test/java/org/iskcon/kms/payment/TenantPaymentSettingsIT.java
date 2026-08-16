package org.iskcon.kms.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.tenancy.TenantSecretStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A temple's own payment gateway (E7).
 *
 * <p>What this is really checking is a boundary: which facts live in our schema and which never
 * touch it. The provider, the public key id and the webhook token are ours to store; the key secret
 * and the webhook secret belong to the secret store, and no query and no endpoint will produce them.
 */
@AutoConfigureMockMvc
@Import({TenantPaymentSettingsIT.StubVerifierConfiguration.class,
		TenantPaymentSettingsIT.StubProbeConfiguration.class})
class TenantPaymentSettingsIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private RecordingProbe probe;

	@Autowired
	private TenantSecretStore secrets;

	private JdbcTemplate admin;
	private UUID tenant;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		probe.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-admin', 'Admin One', 'admin@example.com', '+919812345678', 'TEMPLE_ADMIN', 'ACTIVE')
				""", tenant);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-vol', 'Vol One', 'vol@example.com', '+919812345679', 'VOLUNTEER', 'ACTIVE')
				""", tenant);
		secrets.deleteAll(tenant);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		secrets.deleteAll(tenant);
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM tenant_settings");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("saving keeps the key id in the database and the secret out of it")
	void secretsNeverTouchTheSchema() throws Exception {
		signIn("uid-admin");
		mvc.perform(authed(put("/api/v1/settings/payments"))
						.contentType("application/json")
						.content("""
								{"provider":"RAZORPAY","keyId":"rzp_test_abc123","keySecret":"s3cr3t-value"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.configured").value(true))
				.andExpect(jsonPath("$.keyId").value("rzp_test_abc123"))
				.andExpect(jsonPath("$.verifiedAt").exists())
				// The screen is never told the secret, under any name.
				.andExpect(jsonPath("$.keySecret").doesNotExist());

		Map<String, Object> row = admin.queryForMap(
				"SELECT payment_provider, payment_key_id, payment_webhook_token FROM tenant_settings WHERE tenant_id = ?",
				tenant);
		assert "RAZORPAY".equals(row.get("payment_provider"));
		assert "rzp_test_abc123".equals(row.get("payment_key_id"));
		assert row.get("payment_webhook_token") != null : "a webhook needs an address to arrive at";

		// The one that matters: the secret is in the store, and nowhere in the temple's row.
		assert secrets.get(tenant, TenantSecretStore.Kind.PAYMENT_KEY_SECRET)
				.orElse("").equals("s3cr3t-value");
		String wholeRow = admin.queryForObject(
				"SELECT tenant_settings::text FROM tenant_settings WHERE tenant_id = ?", String.class, tenant);
		assert !wholeRow.contains("s3cr3t-value") : "the secret must not be anywhere in the row";
	}

	@Test
	@DisplayName("credentials the provider refuses are not saved at all")
	void badCredentialsAreRefusedBeforeAnythingIsWritten() throws Exception {
		signIn("uid-admin");
		probe.refuse("Razorpay did not accept that key id and secret.");

		mvc.perform(authed(put("/api/v1/settings/payments"))
						.contentType("application/json")
						.content("""
								{"provider":"RAZORPAY","keyId":"rzp_test_wrong","keySecret":"nope"}"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4946"));

		Integer rows = admin.queryForObject(
				"SELECT count(*) FROM tenant_settings WHERE payment_provider IS NOT NULL", Integer.class);
		assert rows == 0 : "nothing is stored when the provider says no";
		assert secrets.get(tenant, TenantSecretStore.Kind.PAYMENT_KEY_SECRET).isEmpty();
	}

	@Test
	@DisplayName("the webhook address survives a later edit, because the provider already has it")
	void theWebhookTokenIsMintedOnceAndKept() throws Exception {
		signIn("uid-admin");
		save("rzp_test_abc123", "s3cr3t-value");
		String first = admin.queryForObject(
				"SELECT payment_webhook_token FROM tenant_settings WHERE tenant_id = ?", String.class, tenant);

		// Correcting a typo in the key id, without re-typing a secret they cannot see.
		mvc.perform(authed(put("/api/v1/settings/payments"))
						.contentType("application/json")
						.content("""
								{"provider":"RAZORPAY","keyId":"rzp_test_corrected"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.keyId").value("rzp_test_corrected"));

		String second = admin.queryForObject(
				"SELECT payment_webhook_token FROM tenant_settings WHERE tenant_id = ?", String.class, tenant);
		assert first.equals(second) : "changing it would silently stop every confirmation";
		assert secrets.get(tenant, TenantSecretStore.Kind.PAYMENT_KEY_SECRET)
				.orElse("").equals("s3cr3t-value") : "the stored secret is kept, and re-proven";
	}

	@Test
	@DisplayName("a webhook finds its temple by the token, with no tenant established")
	void theWebhookTokenResolvesItsTemple() throws Exception {
		signIn("uid-admin");
		save("rzp_test_abc123", "s3cr3t-value");
		String token = admin.queryForObject(
				"SELECT payment_webhook_token FROM tenant_settings WHERE tenant_id = ?", String.class, tenant);

		TenantContext.clear(); // exactly what an unauthenticated webhook has: nothing
		UUID found = settingsService.tenantForWebhookToken(token).orElse(null);
		assert tenant.equals(found) : "the path has to identify the temple before the body is read";
		assert settingsService.tenantForWebhookToken("not-a-real-token").isEmpty();
	}

	@Test
	@DisplayName("the keys to a temple's money are not a volunteer's to see or change")
	void onlyAnAdministratorMayTouchIt() throws Exception {
		signIn("uid-vol");
		mvc.perform(authed(get("/api/v1/settings/payments"))).andExpect(status().isForbidden());
		mvc.perform(authed(put("/api/v1/settings/payments"))
						.contentType("application/json")
						.content("""
								{"provider":"RAZORPAY","keyId":"rzp_test_abc123","keySecret":"s3cr3t-value"}"""))
				.andExpect(status().isForbidden());
		mvc.perform(authed(post("/api/v1/settings/payments/webhook-secret")))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("revealing the webhook secret hands it over once and writes down who asked")
	void revealingTheWebhookSecretIsAudited() throws Exception {
		signIn("uid-admin");
		save("rzp_test_abc123", "s3cr3t-value");

		mvc.perform(authed(post("/api/v1/settings/payments/webhook-secret")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.webhookSecret").isNotEmpty());

		Integer audited = admin.queryForObject("""
				SELECT count(*) FROM audit_events WHERE action = 'SETTINGS_UPDATED' AND tenant_id = ?
				""", Integer.class, tenant);
		assert audited >= 1 : "a secret handed out is a secret written down";
	}

	@Test
	@DisplayName("the dropdown offers only providers this application can actually talk to")
	void providersAreTheAdaptersThatExist() throws Exception {
		signIn("uid-admin");
		mvc.perform(authed(get("/api/v1/settings/payments/providers")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.value=='RAZORPAY')]").exists());
	}

	// ---- harness ----------------------------------------------------------

	@Autowired
	private TenantPaymentSettingsService settingsService;

	private void save(String keyId, String keySecret) throws Exception {
		mvc.perform(authed(put("/api/v1/settings/payments"))
						.contentType("application/json")
						.content("{\"provider\":\"RAZORPAY\",\"keyId\":\"" + keyId
								+ "\",\"keySecret\":\"" + keySecret + "\"}"))
				.andExpect(status().isOk());
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	/** A probe that never leaves the building, and can be told to refuse. */
	static class RecordingProbe implements PaymentProviderProbe {
		private String refusal;

		void reset() {
			refusal = null;
		}

		void refuse(String reason) {
			refusal = reason;
		}

		@Override
		public String provider() {
			return "RAZORPAY";
		}

		@Override
		public void verify(String keyId, String keySecret) {
			if (refusal != null) {
				throw new PaymentCredentialsRejected(refusal);
			}
		}
	}

	static class StubProbeConfiguration {
		/**
		 * Ahead of the real Razorpay probe in the injected list. {@code @Primary} alone would not do
		 * it: the service takes every probe and picks the first that names this provider, so what
		 * decides is order, not single-bean resolution — and without this the suite would call
		 * Razorpay over the network with a made-up key.
		 */
		@Bean
		@Primary
		@Order(Ordered.HIGHEST_PRECEDENCE)
		RecordingProbe recordingProbe() {
			return new RecordingProbe();
		}
	}

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
