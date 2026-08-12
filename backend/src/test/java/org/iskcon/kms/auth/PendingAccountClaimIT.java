package org.iskcon.kms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier.VerifiedSubject;
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

/**
 * First sign-in account claim (E1-S6).
 *
 * <p>Provisioning creates an account with a {@code pending:} uid; these tests prove that the
 * person can actually sign in — the real path, not the manual uid update the provisioning test
 * once used to fake it — and that the claim is narrow: only a Firebase-verified contact, only a
 * pending row, never a way to inherit an account that is already someone's.
 */
@Import(PendingAccountClaimIT.StubVerifierConfiguration.class)
class PendingAccountClaimIT extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@LocalServerPort
	private int port;

	private JdbcTemplate admin;
	private UUID temple;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		temple = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a pending admin signs in with a verified email and is bound to their real uid")
	void claimsByVerifiedEmail() {
		UUID pending = insertPending(temple, "admin@govinda.example", "+919876500001");

		stubVerifier.accept(new VerifiedSubject("real-uid-1", "admin@govinda.example", null, true));
		ResponseEntity<String> whoami = get("/api/v1/whoami");

		assertThat(whoami.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(whoami.getBody()).contains("TEMPLE_ADMIN");
		assertThat(firebaseUidOf(pending))
				.as("the pending placeholder was replaced with the real Firebase uid")
				.isEqualTo("real-uid-1");
		assertThat(claimAuditCount()).as("the claim is recorded").isEqualTo(1);
	}

	@Test
	@DisplayName("the platform super-admin signs in for the first time and is bound to their real uid")
	void claimsATenantlessSuperAdmin() {
		// The bootstrap gap (E1-S13): a super-admin belongs to no tenant, so the ordinary claim
		// UPDATE is refused by the tenant-scoped write policy — the row is readable but never
		// bindable, and the operator can never sign in. Runs as the unprivileged app role, so it
		// actually exercises the V8 write escape rather than a superuser bypassing RLS.
		UUID pending = insertPendingSuperAdmin("operator@platform.example", "+919800000001");

		stubVerifier.accept(
				new VerifiedSubject("real-superadmin-uid", "operator@platform.example", null, true));
		ResponseEntity<String> whoami = get("/api/v1/whoami");

		assertThat(whoami.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(whoami.getBody()).contains("SUPER_ADMIN");
		assertThat(firebaseUidOf(pending))
				.as("a tenantless super-admin's pending row is bound under RLS, not just read")
				.isEqualTo("real-superadmin-uid");
		// The claim is recorded in the platform audit log (E1-S14), not a temple's — a super-admin
		// belongs to no tenant.
		assertThat(claimAuditCount())
				.as("a platform operator's claim is not written to any temple's audit log")
				.isZero();
		assertThat(platformClaimAuditCount())
				.as("the platform operator's claim is recorded in the platform audit log")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("a pending user signs in by phone OTP (no email) and is claimed")
	void claimsByPhone() {
		UUID pending = insertPending(temple, "cook@govinda.example", "+919876500002");

		// Phone-only token: presence of the phone number is itself proof of OTP verification.
		stubVerifier.accept(new VerifiedSubject("real-uid-2", null, "+919876500002", false));
		ResponseEntity<String> whoami = get("/api/v1/whoami");

		assertThat(whoami.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(firebaseUidOf(pending)).isEqualTo("real-uid-2");
	}

	@Test
	@DisplayName("a matching email that Firebase has NOT verified cannot claim the account")
	void refusesUnverifiedEmail() {
		UUID pending = insertPending(temple, "admin2@govinda.example", "+919876500003");

		stubVerifier.accept(new VerifiedSubject("real-uid-3", "admin2@govinda.example", null, false));
		ResponseEntity<String> whoami = get("/api/v1/whoami");

		assertThat(whoami.getStatusCode())
				.as("an unverified email must not inherit a pending account")
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(firebaseUidOf(pending)).startsWith("pending:");
		assertThat(claimAuditCount()).isZero();
	}

	@Test
	@DisplayName("a verified contact that matches no pending account is simply no account")
	void refusesNoMatch() {
		UUID pending = insertPending(temple, "admin3@govinda.example", "+919876500004");

		stubVerifier.accept(new VerifiedSubject("real-uid-4", "stranger@elsewhere.example", null, true));
		ResponseEntity<String> whoami = get("/api/v1/whoami");

		assertThat(whoami.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(firebaseUidOf(pending)).startsWith("pending:");
	}

	@Test
	@DisplayName("a contact pending at two temples is ambiguous and claims neither")
	void refusesAmbiguousMatch() {
		UUID otherTemple = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
		UUID pendingA = insertPending(temple, "shared@devotee.example", "+919876500005");
		UUID pendingB = insertPending(otherTemple, "shared@devotee.example", "+919876500006");

		stubVerifier.accept(new VerifiedSubject("real-uid-5", "shared@devotee.example", null, true));
		ResponseEntity<String> whoami = get("/api/v1/whoami");

		assertThat(whoami.getStatusCode())
				.as("binding an identity to an arbitrary one of two matches would be wrong")
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(firebaseUidOf(pendingA)).startsWith("pending:");
		assertThat(firebaseUidOf(pendingB)).startsWith("pending:");
	}

	@Test
	@DisplayName("the claim cannot hijack an account that is already someone's")
	void cannotClaimAnAlreadyLinkedAccount() {
		// An active user, already bound to their own uid, with a known email.
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'real-existing-uid', 'Existing Admin', 'existing@govinda.example',
						'+919876500007', 'TEMPLE_ADMIN', 'ACTIVE')
				""", temple);

		// A different person signs in with a NEW uid but the same verified email.
		stubVerifier.accept(
				new VerifiedSubject("attacker-uid", "existing@govinda.example", null, true));
		ResponseEntity<String> whoami = get("/api/v1/whoami");

		assertThat(whoami.getStatusCode())
				.as("only pending rows are claimable; an active account is untouchable this way")
				.isEqualTo(HttpStatus.UNAUTHORIZED);

		String stillLinkedTo = admin.queryForObject(
				"SELECT firebase_uid FROM users WHERE email = 'existing@govinda.example'", String.class);
		assertThat(stillLinkedTo).isEqualTo("real-existing-uid");
	}

	// ---------------------------------------------------------------------

	private UUID insertTenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);
	}

	private UUID insertPending(UUID tenantId, String email, String phone) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Pending Admin', ?, ?, 'TEMPLE_ADMIN', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenantId, "pending:" + UUID.randomUUID(), email, phone);
	}

	private UUID insertPendingSuperAdmin(String email, String phone) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, ?, 'Platform Operator', ?, ?, 'SUPER_ADMIN', 'ACTIVE')
				RETURNING id
				""", UUID.class, "pending:" + UUID.randomUUID(), email, phone);
	}

	private String firebaseUidOf(UUID userId) {
		return admin.queryForObject("SELECT firebase_uid FROM users WHERE id = ?", String.class, userId);
	}

	private int claimAuditCount() {
		Integer count = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'ACCOUNT_CLAIMED'", Integer.class);
		return count == null ? 0 : count;
	}

	private int platformClaimAuditCount() {
		Integer count = admin.queryForObject(
				"SELECT count(*) FROM platform_audit_events WHERE action = 'ACCOUNT_CLAIMED'", Integer.class);
		return count == null ? 0 : count;
	}

	private ResponseEntity<String> get(String path) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth("valid-token");
		return rest.exchange(
				"http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
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

		private VerifiedSubject subject;

		void accept(VerifiedSubject subject) {
			this.subject = subject;
		}

		void reset() {
			this.subject = null;
		}

		@Override
		public VerifiedSubject verify(String idToken) throws InvalidTokenException {
			if (subject == null || !"valid-token".equals(idToken)) {
				throw new InvalidTokenException("Unrecognised token");
			}
			return subject;
		}
	}
}
