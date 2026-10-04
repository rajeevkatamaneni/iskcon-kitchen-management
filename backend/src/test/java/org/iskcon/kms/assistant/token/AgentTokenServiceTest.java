package org.iskcon.kms.assistant.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.iskcon.kms.assistant.token.AgentTokenService.InvalidAgentTokenException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mint and verify, with no Spring and no database: every way a token can be wrong is refused, and
 * the one way it can be right comes back exactly as it went in.
 *
 * <p>The tokens that are wrong in their <em>claims</em> are built here directly with the library and
 * signed with the real key, so each test proves the claim check rather than tripping over the
 * signature check first.
 */
class AgentTokenServiceTest {

	private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
	private static final byte[] OTHER_KEY = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
	private static final Instant NOW = Instant.parse("2026-10-03T06:00:00Z");
	private static final Duration TTL = Duration.ofMinutes(5);

	private static final UUID USER = UUID.randomUUID();
	private static final UUID TENANT = UUID.randomUUID();
	private static final UUID CONVERSATION = UUID.randomUUID();
	private static final Set<String> BOTH_SCOPES =
			Set.of(AgentTokenService.SCOPE_READ, AgentTokenService.SCOPE_PROPOSE);

	private final AgentTokenService service = at(NOW);

	@Test
	@DisplayName("a minted token verifies back to the same person, temple, conversation and scopes")
	void roundTrip() throws Exception {
		String token = service.mint(USER, TENANT, CONVERSATION, BOTH_SCOPES);

		AgentPrincipal principal = service.verify(token);

		assertThat(principal.userId()).isEqualTo(USER);
		assertThat(principal.tenantId()).isEqualTo(TENANT);
		assertThat(principal.conversationId()).isEqualTo(CONVERSATION);
		assertThat(principal.scopes()).isEqualTo(BOTH_SCOPES);
		assertThat(principal.getName()).isEqualTo(USER.toString());
	}

	@Test
	@DisplayName("the token carries exactly the contract's claims, HS256, and lives five minutes")
	void claimsAreTheContractsClaims() throws Exception {
		SignedJWT jwt = SignedJWT.parse(service.mint(USER, TENANT, CONVERSATION, BOTH_SCOPES));
		JWTClaimsSet claims = jwt.getJWTClaimsSet();

		assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
		assertThat(claims.getClaims().keySet())
				.containsExactlyInAnyOrder("sub", "tenant", "conv", "scope", "iat", "exp");
		assertThat(claims.getStringClaim("scope")).isEqualTo("agent:propose agent:read");
		assertThat(Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()))
				.isEqualTo(TTL);
	}

	@Test
	@DisplayName("a token is refused at the moment it expires, and accepted one second before")
	void expired() throws Exception {
		String token = service.mint(USER, TENANT, CONVERSATION, BOTH_SCOPES);

		assertThat(at(NOW.plus(TTL).minusSeconds(1)).verify(token).userId()).isEqualTo(USER);
		assertRefused(at(NOW.plus(TTL)), token);
		assertRefused(at(NOW.plus(Duration.ofHours(1))), token);
	}

	@Test
	@DisplayName("a token signed with a different key is refused")
	void wrongKey() {
		AgentTokenService otherKey = new AgentTokenService(OTHER_KEY, TTL, fixed(NOW));
		String token = otherKey.mint(USER, TENANT, CONVERSATION, BOTH_SCOPES);

		assertRefused(service, token);
	}

	@Test
	@DisplayName("a token whose payload was edited after signing is refused")
	void tamperedPayload() throws Exception {
		String token = service.mint(USER, TENANT, CONVERSATION, BOTH_SCOPES);
		String[] parts = token.split("\\.");

		// Swap the temple for another one and keep the original signature — the attack a tenant
		// claim invites.
		String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
		String forged = payload.replace(TENANT.toString(), UUID.randomUUID().toString());
		assertThat(forged).isNotEqualTo(payload);
		String tampered = parts[0] + "."
				+ Base64.getUrlEncoder().withoutPadding().encodeToString(forged.getBytes(StandardCharsets.UTF_8))
				+ "." + parts[2];

		assertRefused(service, tampered);
	}

	@Test
	@DisplayName("a token with its signature altered is refused")
	void tamperedSignature() {
		String token = service.mint(USER, TENANT, CONVERSATION, BOTH_SCOPES);
		char last = token.charAt(token.length() - 1);
		String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

		assertRefused(service, tampered);
	}

	@Test
	@DisplayName("an unsigned (alg none) token is refused, however good its claims")
	void algNone() {
		String unsigned = new PlainJWT(validClaims().build()).serialize();

		assertRefused(service, unsigned);
	}

	@Test
	@DisplayName("a token signed with another HMAC algorithm is refused, even with the right key")
	void otherAlgorithm() throws Exception {
		byte[] longKey = new byte[64];
		System.arraycopy(KEY, 0, longKey, 0, KEY.length);
		System.arraycopy(KEY, 0, longKey, KEY.length, KEY.length);
		AgentTokenService longKeyed = new AgentTokenService(longKey, TTL, fixed(NOW));
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), validClaims().build());
		jwt.sign(new MACSigner(longKey));

		assertRefused(longKeyed, jwt.serialize());
	}

	@Test
	@DisplayName("each missing claim is refused: sub, tenant, conv, scope, iat, exp")
	void missingClaims() throws Exception {
		assertRefused(service, signed(b -> b.subject(null)));
		assertRefused(service, signed(b -> b.claim("tenant", null)));
		assertRefused(service, signed(b -> b.claim("conv", null)));
		assertRefused(service, signed(b -> b.claim("scope", null)));
		assertRefused(service, signed(b -> b.claim("scope", "  ")));
		assertRefused(service, signed(b -> b.issueTime(null)));
		assertRefused(service, signed(b -> b.expirationTime(null)));

		// And the same builder with nothing removed is accepted, so the refusals above are about the
		// claim each one removed and nothing else.
		assertThat(service.verify(signed(b -> { })).userId()).isEqualTo(USER);
	}

	@Test
	@DisplayName("claims that are present but not UUIDs are refused")
	void malformedClaims() throws Exception {
		assertRefused(service, signed(b -> b.subject("firebase-uid-not-a-user-id")));
		assertRefused(service, signed(b -> b.claim("tenant", "radha-govinda")));
		assertRefused(service, signed(b -> b.claim("conv", 42)));
	}

	@Test
	@DisplayName("a scope we do not know is refused, so a token can never grant a permission by name")
	void unknownScope() throws Exception {
		assertRefused(service, signed(b -> b.claim("scope", "agent:read MANAGE_VENDOR_PAYMENTS")));
		assertRefused(service, signed(b -> b.claim("scope", "agent:write")));

		assertThatThrownBy(() -> service.mint(USER, TENANT, CONVERSATION, Set.of("MANAGE_VENDOR_PAYMENTS")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.mint(USER, TENANT, CONVERSATION, Set.of()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("a token issued in the future is refused")
	void issuedInFuture() throws Exception {
		assertRefused(service, signed(b -> b
				.issueTime(Date.from(NOW.plus(Duration.ofMinutes(2))))
				.expirationTime(Date.from(NOW.plus(Duration.ofMinutes(7))))));
	}

	@Test
	@DisplayName("a token's tenant is what verify reports, so a mismatch is the filter's to catch")
	void tenantIsReportedFaithfully() throws Exception {
		// verify cannot know a person's temple — that is in our users table, and the filter checks it
		// (AgentTokenFilterTest and AgentTokenFilterIT). What verify owes the filter is the tenant
		// exactly as signed, never a default and never a guess.
		UUID otherTenant = UUID.randomUUID();
		String token = service.mint(USER, otherTenant, CONVERSATION, Set.of(AgentTokenService.SCOPE_READ));

		assertThat(service.verify(token).tenantId()).isEqualTo(otherTenant).isNotEqualTo(TENANT);
	}

	@Test
	@DisplayName("garbage, blank and missing tokens are refused")
	void garbage() {
		assertRefused(service, null);
		assertRefused(service, "");
		assertRefused(service, "not-a-jwt");
		assertRefused(service, "a.b.c");
	}

	@Test
	@DisplayName("a key shorter than 256 bits, or not base64, stops start-up")
	void weakKeyRefused() {
		assertThatThrownBy(() -> new AgentTokenService(new byte[31], TTL, fixed(NOW)))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new AgentTokenService("c2hvcnQ=", TTL))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new AgentTokenService("%%% not base64 %%%", TTL))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("with no key configured, a random one is used and works within the instance")
	void generatedKey() throws Exception {
		AgentTokenService a = new AgentTokenService("", TTL);
		AgentTokenService b = new AgentTokenService("", TTL);
		String token = a.mint(USER, TENANT, CONVERSATION, BOTH_SCOPES);

		assertThat(a.verify(token).userId()).isEqualTo(USER);
		// Fails closed across instances rather than sharing anything.
		assertRefused(b, token);
	}

	@Test
	@DisplayName("a configured base64 key is the key: two services given it accept each other's tokens")
	void configuredKey() throws Exception {
		String base64 = Base64.getEncoder().encodeToString(KEY);
		AgentTokenService a = new AgentTokenService(base64, TTL);
		AgentTokenService b = new AgentTokenService(base64, TTL);

		assertThat(b.verify(a.mint(USER, TENANT, CONVERSATION, BOTH_SCOPES)).userId()).isEqualTo(USER);
	}

	// ---------------------------------------------------------------------

	private static AgentTokenService at(Instant instant) {
		return new AgentTokenService(KEY, TTL, fixed(instant));
	}

	private static Clock fixed(Instant instant) {
		return Clock.fixed(instant, ZoneOffset.UTC);
	}

	private static JWTClaimsSet.Builder validClaims() {
		return new JWTClaimsSet.Builder()
				.subject(USER.toString())
				.claim("tenant", TENANT.toString())
				.claim("conv", CONVERSATION.toString())
				.claim("scope", "agent:read")
				.issueTime(Date.from(NOW))
				.expirationTime(Date.from(NOW.plus(TTL)));
	}

	/** A token with valid claims, edited by {@code edit}, signed with the real key. */
	private static String signed(Consumer<JWTClaimsSet.Builder> edit) throws Exception {
		JWTClaimsSet.Builder builder = validClaims();
		edit.accept(builder);
		SignedJWT jwt = new SignedJWT(
				new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), builder.build());
		jwt.sign(new MACSigner(KEY));
		return jwt.serialize();
	}

	private static void assertRefused(AgentTokenService service, String token) {
		assertThatThrownBy(() -> service.verify(token)).isInstanceOf(InvalidAgentTokenException.class);
	}
}
