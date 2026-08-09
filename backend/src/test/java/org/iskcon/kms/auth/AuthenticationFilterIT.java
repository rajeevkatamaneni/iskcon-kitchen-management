package org.iskcon.kms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.iskcon.kms.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies the authentication and tenant-resolution path end to end over real HTTP against a
 * real database.
 *
 * <p>Token verification is stubbed — a live Firebase project would make the suite depend on
 * network access and shared external state. Everything downstream of verification is real:
 * the user lookup through the narrow RLS escape, the disabled-user check, tenant resolution,
 * and the filter's cleanup of thread-local state.
 */
@Import(AuthenticationFilterIT.StubVerifierConfiguration.class)
class AuthenticationFilterIT extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@LocalServerPort
	private int port;

	private JdbcTemplate admin;
	private UUID tenantId;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();

		tenantId = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a request with no token is rejected from a protected endpoint")
	void noTokenIsUnauthorized() {
		ResponseEntity<String> response = get("/api/v1/whoami", null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("an unverifiable token is rejected")
	void invalidTokenIsUnauthorized() {
		ResponseEntity<String> response = get("/api/v1/whoami", "not-a-real-token");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("a valid Firebase token with no application user is rejected")
	void verifiedButUnknownUserIsUnauthorized() {
		// The distinction that matters: Firebase says who they are, we decide whether they
		// have an account here. Signing up with Google does not grant temple access.
		stubVerifier.accept("firebase-uid-with-no-account", "stranger@example.com", "+919000000001");

		ResponseEntity<String> response = get("/api/v1/whoami", "valid-token");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("a disabled user is rejected despite a valid token")
	void disabledUserIsUnauthorized() {
		insertUser("uid-disabled", "disabled@example.com", "+919000000002", "KITCHEN_STAFF", "DISABLED");
		stubVerifier.accept("uid-disabled", "disabled@example.com", "+919000000002");

		ResponseEntity<String> response = get("/api/v1/whoami", "valid-token");

		assertThat(response.getStatusCode())
				.as("access must be revocable by us, without waiting for the token to expire")
				.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("an active user is authenticated and resolved to their own temple")
	void activeUserIsAuthenticatedWithTenant() {
		insertUser("uid-active", "cook@example.com", "+919000000003", "KITCHEN_STAFF", "ACTIVE");
		stubVerifier.accept("uid-active", "cook@example.com", "+919000000003");

		ResponseEntity<String> response = get("/api/v1/whoami", "valid-token");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains(tenantId.toString());
		assertThat(response.getBody()).contains("KITCHEN_STAFF");
	}

	@Test
	@DisplayName("the health endpoint stays reachable without a token")
	void healthIsPublic() {
		ResponseEntity<String> response = get("/health", null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	// ---------------------------------------------------------------------

	private ResponseEntity<String> get(String path, String token) {
		HttpHeaders headers = new HttpHeaders();
		if (token != null) {
			headers.setBearerAuth(token);
		}
		return rest.exchange(
				"http://localhost:" + port + path,
				HttpMethod.GET,
				new HttpEntity<>(headers),
				String.class);
	}

	private void insertUser(String uid, String email, String phone, String role, String status) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", tenantId, uid, "Test User", email, phone, role, status);
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

	/**
	 * Accepts only tokens a test has explicitly registered. Anything else fails verification,
	 * mirroring how a real verifier behaves for a forged token.
	 */
	static class StubTokenVerifier implements TokenVerifier {

		private final Map<String, VerifiedSubject> accepted = new HashMap<>();

		void accept(String uid, String email, String phone) {
			accepted.put("valid-token", new VerifiedSubject(uid, email, phone));
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
