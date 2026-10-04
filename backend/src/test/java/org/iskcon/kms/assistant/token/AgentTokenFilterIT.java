package org.iskcon.kms.assistant.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.RolePermissions;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.iskcon.kms.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The {@code /mcp} security chain over real HTTP against a real database (E10-S2 T5).
 *
 * <p>The endpoint is the test's own, {@code /mcp/test-echo}, so this proves the chain and the filter
 * whatever the MCP server mounted at {@code /mcp} turns out to look like. It echoes back what a tool
 * handler would see: the tenant in {@code TenantContext}, the tenant the <em>database session</em>
 * was actually scoped to, the principal and the authorities.
 *
 * <p><strong>Why this class has a context of its own.</strong> The {@code @Import} of the echo
 * endpoint is part of Spring's context cache key, so this cannot share the suite's common context —
 * the same trade {@code AccessControlEnforcementIT} makes for the same reason. The fixed token key
 * below rides on that one extra context rather than adding another. It is fixed so this class can
 * mint an already-expired token with its own clock; with the in-memory default the key would be
 * unknowable from outside the bean.
 */
@Import(AgentTokenFilterIT.EchoEndpoint.class)
@TestPropertySource(properties = "kms.assistant.token.key=" + AgentTokenFilterIT.KEY_BASE64)
class AgentTokenFilterIT extends AbstractIntegrationTest {

	static final String KEY_BASE64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private AgentTokenService tokens;

	@LocalServerPort
	private int port;

	private JdbcTemplate admin;
	private UUID tenantId;
	private UUID otherTenantId;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		tenantId = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		otherTenantId = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("no token: 401")
	void noToken() {
		assertThat(get("/mcp/test-echo", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("a Firebase token the rest of the API accepts: 401 at /mcp/test-echo")
	void firebaseTokenRefused() {
		insertUser("uid-manager", "KITCHEN_MANAGER", "ACTIVE", tenantId);
		stubVerifier.accept("uid-manager");

		// The same token is good everywhere else — proved here, so the 401 below is about the path.
		assertThat(get("/api/v1/whoami", StubTokenVerifier.TOKEN).getStatusCode()).isEqualTo(HttpStatus.OK);

		ResponseEntity<String> response = get("/mcp/test-echo", StubTokenVerifier.TOKEN);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getBody()).as("a plain 401, no detail").isNull();
	}

	@Test
	@DisplayName("a Firebase token at /mcp itself: 401, so the MCP mount point is inside the agent chain")
	void firebaseTokenRefusedAtMountPoint() {
		insertUser("uid-manager", "KITCHEN_MANAGER", "ACTIVE", tenantId);
		stubVerifier.accept("uid-manager");

		// Under the Firebase chain this request would be authenticated and get as far as the
		// dispatcher. Only the agent chain answers it with a 401.
		assertThat(get("/mcp", StubTokenVerifier.TOKEN).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("a valid agent token: 200, scoped to the token's temple, with scopes plus the person's permissions")
	void validAgentToken() {
		UUID userId = insertUser("uid-manager", "KITCHEN_MANAGER", "ACTIVE", tenantId);
		UUID conversation = UUID.randomUUID();
		String token = tokens.mint(userId, tenantId, conversation,
				Set.of(AgentTokenService.SCOPE_READ, AgentTokenService.SCOPE_PROPOSE));

		ResponseEntity<Map<String, Object>> response = echo(token);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		Map<String, Object> body = response.getBody();
		assertThat(body.get("tenantContext")).isEqualTo(tenantId.toString());
		assertThat(body.get("databaseTenant"))
				.as("the database session itself is scoped, so RLS applies to every tool")
				.isEqualTo(tenantId.toString());
		assertThat(body.get("principalType")).isEqualTo(AgentPrincipal.class.getSimpleName());
		assertThat(body.get("userId")).isEqualTo(userId.toString());
		assertThat(body.get("conversationId")).isEqualTo(conversation.toString());

		Set<String> expected = new TreeSet<>(Set.of("agent:read", "agent:propose"));
		RolePermissions.forRole(User.Role.KITCHEN_MANAGER).forEach(p -> expected.add(p.name()));
		assertThat(new TreeSet<>(authorities(body)))
				.as("exactly the scopes plus the person's own permissions — no role, nothing else")
				.isEqualTo(expected);
	}

	@Test
	@DisplayName("a disabled user: 401, even though their token is valid and unexpired")
	void disabledUser() {
		UUID userId = insertUser("uid-disabled", "KITCHEN_MANAGER", "ACTIVE", tenantId);
		String token = readToken(userId, tenantId);
		assertThat(get("/mcp/test-echo", token).getStatusCode()).isEqualTo(HttpStatus.OK);

		admin.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", userId);

		assertThat(get("/mcp/test-echo", token).getStatusCode())
				.as("disabling takes effect on the agent's next call, not when the token expires")
				.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("a token naming another temple than the user's: 401")
	void tenantMismatch() {
		UUID userId = insertUser("uid-manager", "KITCHEN_MANAGER", "ACTIVE", tenantId);

		assertThat(get("/mcp/test-echo", readToken(userId, otherTenantId)).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("a token for a user who does not exist: 401")
	void unknownUser() {
		assertThat(get("/mcp/test-echo", readToken(UUID.randomUUID(), tenantId)).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("an expired agent token: 401")
	void expiredToken() {
		UUID userId = insertUser("uid-manager", "KITCHEN_MANAGER", "ACTIVE", tenantId);
		AgentTokenService tenMinutesAgo = new AgentTokenService(
				Base64.getDecoder().decode(KEY_BASE64),
				Duration.ofMinutes(5),
				Clock.fixed(Instant.now().minus(Duration.ofMinutes(10)), ZoneOffset.UTC));
		String expired = tenMinutesAgo.mint(userId, tenantId, UUID.randomUUID(), Set.of(AgentTokenService.SCOPE_READ));

		assertThat(get("/mcp/test-echo", expired).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("a token signed with another key: 401")
	void wrongKey() {
		UUID userId = insertUser("uid-manager", "KITCHEN_MANAGER", "ACTIVE", tenantId);
		AgentTokenService otherKey = new AgentTokenService(
				"fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8),
				Duration.ofMinutes(5), Clock.systemUTC());

		assertThat(get("/mcp/test-echo", otherKey.mint(userId, tenantId, UUID.randomUUID(),
				Set.of(AgentTokenService.SCOPE_READ))).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("a token without agent:read: 403")
	void noReadScope() {
		UUID userId = insertUser("uid-manager", "KITCHEN_MANAGER", "ACTIVE", tenantId);
		String proposeOnly = tokens.mint(userId, tenantId, UUID.randomUUID(), Set.of(AgentTokenService.SCOPE_PROPOSE));

		assertThat(get("/mcp/test-echo", proposeOnly).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	@DisplayName("the agent can do no more than the person: a cook's agent is refused vendor payments")
	void personsPermissionsStillHold() {
		UUID userId = insertUser("uid-cook", "KITCHEN_STAFF", "ACTIVE", tenantId);

		assertThat(get("/mcp/test-echo/payments", readToken(userId, tenantId)).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	@DisplayName("an agent token is worthless outside /mcp: 401 at /api/v1/whoami")
	void agentTokenRefusedElsewhere() {
		UUID userId = insertUser("uid-manager", "KITCHEN_MANAGER", "ACTIVE", tenantId);

		assertThat(get("/api/v1/whoami", readToken(userId, tenantId)).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// ---------------------------------------------------------------------

	private String readToken(UUID userId, UUID tenant) {
		return tokens.mint(userId, tenant, UUID.randomUUID(), Set.of(AgentTokenService.SCOPE_READ));
	}

	@SuppressWarnings("unchecked")
	private static List<String> authorities(Map<String, Object> body) {
		return (List<String>) body.get("authorities");
	}

	private ResponseEntity<String> get(String path, String token) {
		return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
	}

	private ResponseEntity<Map<String, Object>> echo(String token) {
		return rest.exchange(url("/mcp/test-echo"), HttpMethod.GET, new HttpEntity<>(headers(token)),
				new ParameterizedTypeReference<>() { });
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}

	private static HttpHeaders headers(String token) {
		HttpHeaders headers = new HttpHeaders();
		if (token != null) {
			headers.setBearerAuth(token);
		}
		return headers;
	}

	private UUID insertTenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);
	}

	private UUID insertUser(String uid, String role, String status, UUID tenant) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test User', ?, '+919000000000', ?, ?)
				RETURNING id
				""", UUID.class, tenant, uid, uid + "@example.com", role, status);
	}

	// ---------------------------------------------------------------------

	/**
	 * What a tool handler would see. Test sources only; never part of the application.
	 */
	@RestController
	@RequestMapping("/mcp/test-echo")
	static class EchoEndpoint {

		private final JdbcTemplate jdbc;

		EchoEndpoint(DataSource dataSource) {
			this.jdbc = new JdbcTemplate(dataSource);
		}

		@GetMapping
		Map<String, Object> echo() {
			Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			AgentPrincipal principal = (AgentPrincipal) authentication.getPrincipal();
			return Map.of(
					"tenantContext", TenantContext.get().map(UUID::toString).orElse("none"),
					// Through the application's own tenant-aware DataSource, so this is the setting
					// RLS policies actually read.
					"databaseTenant", jdbc.queryForObject("SELECT current_setting('app.tenant_id', true)", String.class),
					"principalType", principal.getClass().getSimpleName(),
					"userId", principal.userId().toString(),
					"conversationId", principal.conversationId().toString(),
					"authorities", authentication.getAuthorities().stream()
							.map(GrantedAuthority::getAuthority)
							.collect(Collectors.toList()));
		}

		@GetMapping("/payments")
		@PreAuthorize("hasAuthority('MANAGE_VENDOR_PAYMENTS')")
		String payments() {
			return "ok";
		}
	}
}
