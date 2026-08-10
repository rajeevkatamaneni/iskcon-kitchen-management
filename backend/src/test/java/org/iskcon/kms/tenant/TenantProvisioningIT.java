package org.iskcon.kms.tenant;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end provisioning: a super-admin creates a temple, and someone can immediately
 * administer it.
 *
 * <p>The acceptance criterion from the story is the last of these tests — a newly provisioned
 * temple must be usable, not merely present in a table.
 */
@Import(TenantProvisioningIT.StubVerifierConfiguration.class)
class TenantProvisioningIT extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@LocalServerPort
	private int port;

	private JdbcTemplate admin;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a super-admin provisions a temple and its first administrator together")
	void provisionsTempleWithAdministrator() {
		signInAsSuperAdmin();

		ResponseEntity<String> response = post("/api/v1/tenants", validRequest());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		Integer tenants = admin.queryForObject(
				"SELECT count(*) FROM tenants WHERE slug = 'radha-govinda'", Integer.class);
		Integer admins = admin.queryForObject(
				"SELECT count(*) FROM users WHERE role = 'TEMPLE_ADMIN'", Integer.class);

		assertThat(tenants).isEqualTo(1);
		assertThat(admins)
				.as("a temple without an administrator is a record nobody can reach")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("coordinates and timezone are stored, because the calendar depends on them")
	void storesLocationForCalendar() {
		signInAsSuperAdmin();
		post("/api/v1/tenants", validRequest());

		Map<String, Object> tenant = admin.queryForMap(
				"SELECT latitude, longitude, timezone FROM tenants WHERE slug = 'radha-govinda'");

		assertThat(tenant.get("timezone")).isEqualTo("Asia/Kolkata");
		assertThat(tenant.get("latitude")).isNotNull();
		assertThat(tenant.get("longitude")).isNotNull();
	}

	@Test
	@DisplayName("a duplicate web address is refused with a code the user can quote")
	void refusesDuplicateSlug() {
		signInAsSuperAdmin();
		post("/api/v1/tenants", validRequest());

		ResponseEntity<String> second = post("/api/v1/tenants", validRequest());

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(second.getBody()).contains("KMS-4901");
		assertThat(second.getBody()).contains("Another temple is already using that web address.");
	}

	@Test
	@DisplayName("an unusable timezone is refused rather than silently accepted")
	void refusesInvalidTimezone() {
		// A wrong timezone shifts every Ekadashi calculation for that temple, and nothing
		// downstream would reveal it. Better to fail here than to produce a subtly wrong
		// calendar for a year.
		signInAsSuperAdmin();

		Map<String, Object> body = validRequest();
		body.put("timezone", "Asia/Bengaluru");

		ResponseEntity<String> response = post("/api/v1/tenants", body);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).contains("KMS-4001");
	}

	@Test
	@DisplayName("invalid coordinates are refused with per-field messages")
	void refusesInvalidCoordinates() {
		signInAsSuperAdmin();

		Map<String, Object> body = validRequest();
		body.put("latitude", 200);

		ResponseEntity<String> response = post("/api/v1/tenants", body);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).contains("Latitude must be between -90 and 90.");
	}

	@Test
	@DisplayName("a temple admin cannot provision temples")
	void templeAdminCannotProvision() {
		// Running a temple is not running the platform.
		UUID tenantId = seedTenant();
		signInAs("uid-temple-admin", "TEMPLE_ADMIN", tenantId);

		assertThat(post("/api/v1/tenants", validRequest()).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	@DisplayName("an unauthenticated caller cannot provision temples")
	void anonymousCannotProvision() {
		assertThat(post("/api/v1/tenants", validRequest()).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("the new temple's administrator can sign in immediately")
	void newAdministratorCanSignInImmediately() {
		// The story's acceptance criterion: provisioning must produce a working temple, not
		// just a row. The administrator exists before they have ever touched Firebase — the
		// temple decides who administers it, not whoever signs up first.
		signInAsSuperAdmin();
		post("/api/v1/tenants", validRequest());

		String pendingUid = admin.queryForObject(
				"SELECT firebase_uid FROM users WHERE role = 'TEMPLE_ADMIN'", String.class);
		assertThat(pendingUid).startsWith("pending:");

		// Simulate that person completing Firebase sign-in for the first time.
		admin.update("UPDATE users SET firebase_uid = ? WHERE role = 'TEMPLE_ADMIN'", "uid-first-login");
		stubVerifier.accept("uid-first-login");

		ResponseEntity<String> whoami = get("/api/v1/whoami");

		assertThat(whoami.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(whoami.getBody()).contains("TEMPLE_ADMIN");
	}

	// ---------------------------------------------------------------------

	private Map<String, Object> validRequest() {
		Map<String, Object> body = new HashMap<>();
		body.put("name", "Sri Sri Radha Govinda Temple");
		body.put("slug", "radha-govinda");
		body.put("address", "Bengaluru, Karnataka");
		body.put("latitude", 12.9716);
		body.put("longitude", 77.5946);
		body.put("timezone", "Asia/Kolkata");
		body.put("currency", "INR");
		body.put("is80gApproved", true);
		body.put("adminName", "Karuna Murthy Das");
		body.put("adminEmail", "admin@example.com");
		body.put("adminPhone", "+919876543210");
		return body;
	}

	private void signInAsSuperAdmin() {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, 'uid-super', 'Platform Operator', 'super@example.com',
						'+919000000001', 'SUPER_ADMIN', 'ACTIVE')
				""");
		stubVerifier.accept("uid-super");
	}

	private void signInAs(String uid, String role, UUID tenantId) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test User', ?, '+919000000002', ?, 'ACTIVE')
				""", tenantId, uid, uid + "@example.com", role);
		stubVerifier.accept(uid);
	}

	private UUID seedTenant() {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('existing', 'Existing Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
	}

	private ResponseEntity<String> post(String path, Map<String, Object> body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		if (!stubVerifier.isEmpty()) {
			headers.setBearerAuth("valid-token");
		}
		return rest.postForEntity(
				"http://localhost:" + port + path, new HttpEntity<>(body, headers), String.class);
	}

	private ResponseEntity<String> get(String path) {
		HttpHeaders headers = new HttpHeaders();
		if (!stubVerifier.isEmpty()) {
			headers.setBearerAuth("valid-token");
		}
		return rest.exchange(
				"http://localhost:" + port + path,
				org.springframework.http.HttpMethod.GET,
				new HttpEntity<>(headers),
				String.class);
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

		boolean isEmpty() {
			return accepted.isEmpty();
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
