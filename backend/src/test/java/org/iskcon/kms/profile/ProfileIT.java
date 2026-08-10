package org.iskcon.kms.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The self-service profile: a user's preferred channel and their communication consent (E1-S8).
 *
 * <p>Every request acts on the authenticated caller's own row, so these also stand as evidence
 * that the endpoints are self-scoped — the id is never taken from the request. MockMvc because a
 * profile update is a PATCH, which the JDK's default HTTP client cannot issue.
 */
@AutoConfigureMockMvc
@Import(ProfileIT.StubVerifierConfiguration.class)
class ProfileIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID self;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();

		UUID temple = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		self = insertUser(temple, "uid-self", "self@govinda.example", "VOLUNTEER");
		stubVerifier.accept("uid-self");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("the profile shows the channel and that consent is still needed")
	void showsChannelAndConsentStatus() throws Exception {
		mvc.perform(authed(get("/api/v1/profile")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("self@govinda.example"))
				.andExpect(jsonPath("$.preferredChannel").value("WHATSAPP"))
				.andExpect(jsonPath("$.consentNeeded").value(true))
				.andExpect(jsonPath("$.currentConsentVersion").value(CommunicationConsent.CURRENT_VERSION))
				.andExpect(jsonPath("$.consentText").isNotEmpty());
	}

	@Test
	@DisplayName("changing the preferred channel persists and is returned")
	void changesPreferredChannel() throws Exception {
		mvc.perform(authed(patch("/api/v1/profile"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"preferredChannel\":\"SMS\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.preferredChannel").value("SMS"));

		assertThat(channelOf(self)).isEqualTo("SMS");
	}

	@Test
	@DisplayName("an unrecognised channel is refused as ordinary validation")
	void rejectsUnknownChannel() throws Exception {
		mvc.perform(authed(patch("/api/v1/profile"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"preferredChannel\":\"CARRIER_PIGEON\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4001"));

		assertThat(channelOf(self)).as("nothing changed").isEqualTo("WHATSAPP");
	}

	@Test
	@DisplayName("giving consent records the moment and the wording accepted")
	void recordsConsent() throws Exception {
		mvc.perform(authed(post("/api/v1/profile/consent")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.consentNeeded").value(false))
				.andExpect(jsonPath("$.consentVersion").value(CommunicationConsent.CURRENT_VERSION));

		Map<String, Object> row = admin.queryForMap(
				"SELECT contact_consent_at, consent_version FROM users WHERE id = ?", self);
		assertThat(row.get("contact_consent_at")).as("the moment is stamped").isNotNull();
		assertThat(row.get("consent_version")).isEqualTo(CommunicationConsent.CURRENT_VERSION);
	}

	@Test
	@DisplayName("an unauthenticated caller has no profile")
	void unauthenticatedRejected() throws Exception {
		mvc.perform(get("/api/v1/profile")).andExpect(status().isUnauthorized());
	}

	// ---------------------------------------------------------------------

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authed(
			org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private String channelOf(UUID userId) {
		return admin.queryForObject(
				"SELECT preferred_channel FROM users WHERE id = ?", String.class, userId);
	}

	private UUID insertTenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);
	}

	private UUID insertUser(UUID tenantId, String uid, String email, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Devotee', ?, '+919876500010', ?, 'ACTIVE')
				RETURNING id
				""", UUID.class, tenantId, uid, email, role);
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
			accepted.put("valid-token", new VerifiedSubject(uid, uid + "@govinda.example", null));
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
