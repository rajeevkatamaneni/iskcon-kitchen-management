package org.iskcon.kms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verifies that permissions are actually enforced over HTTP, not merely declared.
 *
 * <p>{@link RolePermissionsTest} asserts the policy is correct; this asserts the application
 * obeys it. Both are needed — a correct policy that no endpoint consults protects nothing.
 *
 * <p>Endpoints here are defined by the test rather than borrowed from the application, so this
 * exercises the enforcement mechanism itself and does not break every time a real endpoint moves.
 */
@Import({AccessControlEnforcementIT.TestEndpoints.class,
		AccessControlEnforcementIT.StubVerifierConfiguration.class})
class AccessControlEnforcementIT extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate rest;

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
	@DisplayName("kitchen staff may manage inventory")
	void kitchenStaffMayManageInventory() {
		signInAs("KITCHEN_STAFF");

		assertThat(get("/test/inventory").getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("kitchen staff may not touch vendor payments")
	void kitchenStaffMayNotPayVendors() {
		// The story's named example. Money is not a kitchen concern.
		signInAs("KITCHEN_STAFF");

		assertThat(get("/test/payments").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	@DisplayName("a volunteer may not touch vendor payments")
	void volunteerMayNotPayVendors() {
		signInAs("VOLUNTEER");

		ResponseEntity<String> response = get("/test/payments");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getBody())
				.as("the response must not disclose which permission was missing")
				.doesNotContain("MANAGE_VENDOR_PAYMENTS");
	}

	@Test
	@DisplayName("a volunteer may view their own shifts")
	void volunteerMayViewOwnShifts() {
		signInAs("VOLUNTEER");

		assertThat(get("/test/my-shifts").getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("a temple admin may pay vendors")
	void templeAdminMayPayVendors() {
		signInAs("TEMPLE_ADMIN");

		assertThat(get("/test/payments").getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("a temple admin may not provision tenants")
	void templeAdminMayNotProvisionTenants() {
		// Running a temple is not running the platform.
		signInAs("TEMPLE_ADMIN");

		assertThat(get("/test/tenants").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	@DisplayName("an unauthenticated caller gets 401, not 403")
	void unauthenticatedIsUnauthorized() {
		// The distinction is worth keeping: 401 means "tell me who you are", 403 means "I know
		// who you are and the answer is no". Conflating them makes client behaviour guesswork.
		assertThat(get("/test/inventory").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// ---------------------------------------------------------------------

	private void signInAs(String role) {
		String uid = "uid-" + role.toLowerCase();
		String email = role.toLowerCase() + "@example.com";
		String phone = "+9190000000" + (10 + role.length() % 80);

		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
				""", tenantId, uid, "Test " + role, email, phone, role);

		stubVerifier.accept(uid, email, phone);
	}

	private ResponseEntity<String> get(String path) {
		HttpHeaders headers = new HttpHeaders();
		if (!stubVerifier.isEmpty()) {
			headers.setBearerAuth("valid-token");
		}
		return rest.exchange(
				"http://localhost:" + port + path,
				HttpMethod.GET,
				new HttpEntity<>(headers),
				String.class);
	}

	// ---------------------------------------------------------------------

	@RestController
	@RequestMapping("/test")
	static class TestEndpoints {

		@GetMapping("/inventory")
		@PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
		String inventory() {
			return "ok";
		}

		@GetMapping("/payments")
		@PreAuthorize("hasAuthority('MANAGE_VENDOR_PAYMENTS')")
		String payments() {
			return "ok";
		}

		@GetMapping("/my-shifts")
		@PreAuthorize("hasAuthority('VIEW_OWN_SHIFTS')")
		String myShifts() {
			return "ok";
		}

		@GetMapping("/tenants")
		@PreAuthorize("hasAuthority('MANAGE_TENANTS')")
		String tenants() {
			return "ok";
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

		void accept(String uid, String email, String phone) {
			accepted.put("valid-token", new VerifiedSubject(uid, email, phone));
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
