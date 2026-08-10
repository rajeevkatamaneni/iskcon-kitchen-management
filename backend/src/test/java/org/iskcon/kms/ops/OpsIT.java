package org.iskcon.kms.ops;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The Super-Admin ops drill-in: an operator sees one temple's send health, and a temple admin is
 * refused the platform-operations endpoints entirely.
 */
@AutoConfigureMockMvc
@Import(OpsIT.StubVerifierConfiguration.class)
class OpsIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID temple;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		temple = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a super-admin drills into a temple and sees today's sends and a failed one")
	void superAdminSeesTenantOps() throws Exception {
		seedNotification("SENT", "WHATSAPP");
		seedNotification("FAILED", null);
		signInAsSuperAdmin();

		mvc.perform(authed(get("/api/v1/ops/tenants/{id}", temple)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tenantName").value("Sri Sri Radha Govinda Temple"))
				.andExpect(jsonPath("$.sentToday").value(1))
				.andExpect(jsonPath("$.failedToday").value(1))
				.andExpect(jsonPath("$.recentFailures[0].template").value("SHIFT_REMINDER"))
				.andExpect(jsonPath("$.lastCalendarPrecompute").doesNotExist());
	}

	@Test
	@DisplayName("a temple admin is refused the platform-operations endpoints")
	void templeAdminIsForbidden() throws Exception {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-admin', 'Temple Admin', 'admin@govinda.example', '+919876500050',
						'TEMPLE_ADMIN', 'ACTIVE')
				""", temple);
		stubVerifier.accept("uid-admin");

		mvc.perform(authed(get("/api/v1/ops/tenants/{id}", temple))).andExpect(status().isForbidden());
		mvc.perform(authed(get("/api/v1/ops/tenants"))).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authed(
			org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private void seedNotification(String status, String finalChannel) {
		admin.update("""
				INSERT INTO notifications (tenant_id, recipient_label, to_phone, template,
						preferred_channel, status, final_channel)
				VALUES (?, 'Test Devotee', '+919876500051', 'SHIFT_REMINDER', 'WHATSAPP', ?, ?)
				""", temple, status, finalChannel);
	}

	private void signInAsSuperAdmin() {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, 'uid-super', 'Platform Operator', 'super@example.com', '+919000000001',
						'SUPER_ADMIN', 'ACTIVE')
				""");
		stubVerifier.accept("uid-super");
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
