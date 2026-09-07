package org.iskcon.kms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What a refused request actually says, over real HTTP against a real database.
 *
 * <p>Every other failure in this product answers with the {@code ErrorResponse} contract: a
 * permanent reference code, plain language, and a next step. Authentication failures did not,
 * because they happen in the security filter chain and never reach {@code DispatcherServlet}, so
 * {@code GlobalExceptionHandler} never sees them — the chain answered with a bodyless 401 and two
 * carefully written codes, {@code ACCOUNT_DISABLED} and {@code NO_ACCOUNT_AT_TEMPLE}, sat unused.
 * The web app, given nothing to read, guessed, and told every disabled volunteer that they had no
 * account at this temple.
 *
 * <p>Half of this class checks the codes now arrive. The other half — and it is the more important
 * half — checks that nothing public broke on the way. The mechanism is a request attribute rather
 * than a thrown exception precisely so that a request carrying a useless token can still reach a
 * webhook, an unsubscribe link or the temple list; an implementation that threw would pass the
 * first three tests here and fail the rest, which is why the rest exist.
 *
 * <p>Token verification is stubbed, reusing {@link AuthenticationFilterIT}'s stub deliberately: it
 * makes this class share that one's Spring context instead of starting a second, and there is no
 * second definition of "an accepted token" to drift out of step with the first. Everything below
 * verification is real.
 */
@Import(AuthenticationFilterIT.StubVerifierConfiguration.class)
class AuthenticationFailureIT extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private AuthenticationFilterIT.StubTokenVerifier stubVerifier;

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
				VALUES ('failure-temple', 'Sri Sri Radha Krishna Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
	}

	@AfterEach
	void tearDown() {
		// Joining a temple records the person's own arrival, and audit_events references users with
		// ON DELETE RESTRICT — a trail must not lose its subject. So the trail goes first, as the
		// privileged role, which the append-only rule does not bind. Leaving this out does not fail
		// one test: the failed DELETE leaves the tenant behind too, and every later class sharing
		// this database then collides on a temple slug. It cost sixteen red tests to find out.
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// --- The two codes that had nowhere to be thrown from ------------------

	@Test
	@DisplayName("a disabled user is told their account was disabled, with the code to quote")
	void disabledUserGetsTheDisabledCode() {
		insertUser("uid-disabled", "disabled@example.com", "+919000000101", "KITCHEN_STAFF", "DISABLED");
		stubVerifier.accept("uid-disabled", "disabled@example.com", "+919000000101");

		ResponseEntity<String> response = get("/api/v1/whoami", "valid-token");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getHeaders().getContentType())
				.isNotNull()
				.satisfies(type -> assertThat(type.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue());
		assertThat(response.getBody())
				.as("the whole contract: a code to quote, what happened, and what to do about it")
				.contains(ErrorCode.ACCOUNT_DISABLED.reference())
				.contains(ErrorCode.ACCOUNT_DISABLED.whatHappened())
				.contains(ErrorCode.ACCOUNT_DISABLED.whatToDo());
		assertThat(ErrorCode.ACCOUNT_DISABLED.reference())
				.as("the number on the screenshot somebody will one day quote")
				.isEqualTo("KMS-400019");
	}

	@Test
	@DisplayName("a verified person who belongs to no temple is told that, and not that they are disabled")
	void unaffiliatedVisitorGetsTheNoAccountCode() {
		// Firebase knows them; we do not. That is a different fact from an account being withdrawn,
		// and until now both arrived as the same empty 401.
		stubVerifier.accept("uid-no-membership", "stranger@example.com", "+919000000102");

		ResponseEntity<String> response = get("/api/v1/whoami", "valid-token");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getBody())
				.contains(ErrorCode.NO_ACCOUNT_AT_TEMPLE.reference())
				.contains(ErrorCode.NO_ACCOUNT_AT_TEMPLE.whatHappened())
				.doesNotContain(ErrorCode.ACCOUNT_DISABLED.reference());
		assertThat(ErrorCode.NO_ACCOUNT_AT_TEMPLE.reference()).isEqualTo("KMS-400020");
	}

	// --- What must not have broken ----------------------------------------

	@Test
	@DisplayName("the temple list is still readable with no token at all")
	void publicTempleListNeedsNoToken() {
		ResponseEntity<String> response = get("/api/v1/temples", null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("Sri Sri Radha Krishna Temple");
	}

	@Test
	@DisplayName("the temple list is still readable with a token that cannot be verified")
	void publicTempleListSurvivesAnUnusableToken() {
		// A devotee whose session went stale while the temple picker was open. The picker is the one
		// screen that must work for somebody with no usable credentials at all.
		ResponseEntity<String> response = get("/api/v1/temples", "not-a-real-token");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("a disabled user's token does not close a public endpoint")
	void publicTempleListSurvivesADisabledUsersToken() {
		// The specific way this change could have gone wrong. The filter now records a refusal for
		// this person — and the entry point must never be reached, because nothing here required
		// them to be anybody. Had the filter thrown instead of recording, this would be a 401.
		insertUser("uid-disabled-2", "gone@example.com", "+919000000103", "VOLUNTEER", "DISABLED");
		stubVerifier.accept("uid-disabled-2", "gone@example.com", "+919000000103");

		ResponseEntity<String> response = get("/api/v1/temples", "valid-token");

		assertThat(response.getStatusCode())
				.as("a refusal recorded for one request must not leak into a path that is public")
				.isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("the unsubscribe link still answers without an account")
	void unsubscribeStaysPublic() {
		// Somebody opening a link from an email has no session and never will. The token here is
		// nonsense, so the controller refuses it on its own terms — 400, its own answer, which is
		// the proof that the request reached the controller rather than being turned away above it.
		ResponseEntity<String> response = rest.exchange(
				"http://localhost:" + port + "/api/v1/public/unsubscribe?token=nonsense",
				HttpMethod.POST,
				new HttpEntity<>(new HttpHeaders()),
				String.class);

		assertThat(response.getStatusCode())
				.as("reached the controller; a 401 would mean the filter chain turned it away")
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("a verified visitor with no membership may still join a temple")
	void joinFlowStaysOpenToAnUnaffiliatedVisitor() {
		// The branch that records NO_ACCOUNT_AT_TEMPLE sits beside the one that authenticates this
		// person as an unaffiliated visitor. Recording a refusal on the first must not have leaked
		// into the second: they are still exactly who they were, and the join flow is the one thing
		// they may do.
		stubVerifier.accept("uid-joining", "joining@example.com", "+919000000104");

		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth("valid-token");
		headers.setContentType(MediaType.APPLICATION_JSON);

		ResponseEntity<String> response = rest.exchange(
				"http://localhost:" + port + "/api/v1/temples/" + tenantId + "/join",
				HttpMethod.POST,
				new HttpEntity<>(Map.of(
						"firstName", "Anand",
						"lastName", "Das",
						"phone", "+919000000104",
						"email", "joining@example.com"), headers),
				String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).contains(tenantId.toString());
	}

	// --- The half deliberately left alone ---------------------------------

	@Test
	@DisplayName("a token that cannot be verified is still refused without saying why")
	void unverifiableTokenStaysBodyless() {
		// Held, not forgotten. SESSION_EXPIRED (KMS-400018) would live here, and whether "expired"
		// is the one disclosure safe enough to make is an open question with Rajeev — TokenVerifier
		// states the opposite policy in as many words. Until he answers, this stays what it was.
		ResponseEntity<String> response = get("/api/v1/whoami", "not-a-real-token");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getBody()).isNullOrEmpty();
	}

	@Test
	@DisplayName("no token at all is still refused without a body")
	void absentTokenStaysBodyless() {
		ResponseEntity<String> response = get("/api/v1/whoami", null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getBody()).isNullOrEmpty();
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
}
