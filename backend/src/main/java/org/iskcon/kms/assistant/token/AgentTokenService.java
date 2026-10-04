package org.iskcon.kms.assistant.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Mints and verifies the Kitchen Assistant's agent token (E10-S2).
 *
 * <p><strong>What the token is for.</strong> When a person sends the assistant a message, the API
 * starts {@code claude -p} and hands it one of these. The model then calls our {@code /mcp} tools
 * with it, and {@link AgentTokenFilter} turns it back into that person, at that temple, for that
 * conversation. The person's Firebase token never leaves the API: it is a credential for everything
 * they can do in the product, and a process we do not control has no business holding it.
 *
 * <p><strong>Shape.</strong> A JWT signed with HS256, valid for five minutes, carrying {@code sub}
 * (our {@code users.id}), {@code tenant}, {@code conv}, {@code scope} (space-separated), {@code iat}
 * and {@code exp}. HS256 rather than a key pair because the API both mints and verifies; nobody else
 * ever needs to check one, so there is no public half worth having. Five minutes because one token is
 * minted per message and a run is killed at sixty seconds — anything longer is only a longer window
 * for a leaked token.
 *
 * <p><strong>What verification refuses, and why each matters.</strong> Any algorithm but HS256, so an
 * {@code alg: none} or algorithm-confusion token is never even given to the MAC check. A wrong
 * signature. A missing or unreadable claim. An expired token, or one issued in the future. And any
 * scope this class does not know — because scopes become Spring authorities, and a token naming
 * {@code MANAGE_VENDOR_PAYMENTS} as a "scope" must not be able to grant it. Every refusal is the same
 * {@link InvalidAgentTokenException}; the filter answers all of them with one bodyless 401, since the
 * caller is a program and telling it which check failed helps an attacker more than anyone else.
 *
 * <p><strong>The key.</strong> {@code kms.assistant.token.key}, base64, at least 256 bits — the
 * minimum HS256 accepts, and enforced at start-up rather than discovered at the first message.
 * Unset, a random key is generated in memory: right for tests and local development, and safe
 * everywhere else because it fails closed — a token minted by one instance is simply refused by
 * another. A deployment that wants the assistant to work across instances mounts the key from
 * Secret Manager (E10-S6).
 */
@Service
public class AgentTokenService {

	private static final Logger log = LoggerFactory.getLogger(AgentTokenService.class);

	/** Read-only tools. Every agent token carries it; {@code /mcp} refuses a token without it. */
	public static final String SCOPE_READ = "agent:read";

	/** Tools that write a proposal for the person to confirm, and change nothing themselves. */
	public static final String SCOPE_PROPOSE = "agent:propose";

	/** The only scopes a token may carry. Anything else is refused at both mint and verify. */
	public static final Set<String> KNOWN_SCOPES = Set.of(SCOPE_READ, SCOPE_PROPOSE);

	static final String CLAIM_TENANT = "tenant";
	static final String CLAIM_CONVERSATION = "conv";
	static final String CLAIM_SCOPE = "scope";

	/** HS256 needs a key at least as long as its output. Shorter is refused by the library too. */
	private static final int MINIMUM_KEY_BYTES = 32;

	/**
	 * How far in the future an {@code iat} may be before it is treated as forged. Instances of the
	 * API do not share a clock exactly, and a token minted on one may be checked on another.
	 */
	private static final Duration ISSUED_AT_TOLERANCE = Duration.ofSeconds(30);

	private final MACSigner signer;
	private final MACVerifier verifier;
	private final Duration ttl;
	private final Clock clock;

	@Autowired
	public AgentTokenService(
			@Value("${kms.assistant.token.key:}") String base64Key,
			@Value("${kms.assistant.token.ttl:PT5M}") Duration ttl) {
		this(resolveKey(base64Key), ttl, Clock.systemUTC());
	}

	/** For tests: an explicit key and clock, so expiry can be proved without waiting for it. */
	AgentTokenService(byte[] key, Duration ttl, Clock clock) {
		if (key.length < MINIMUM_KEY_BYTES) {
			throw new IllegalStateException(
					"kms.assistant.token.key must be at least 256 bits (32 bytes) once base64-decoded");
		}
		if (ttl.isNegative() || ttl.isZero()) {
			throw new IllegalStateException("kms.assistant.token.ttl must be positive");
		}
		try {
			this.signer = new MACSigner(key);
			this.verifier = new MACVerifier(key);
		} catch (JOSEException e) {
			throw new IllegalStateException("kms.assistant.token.key is not a usable HS256 key", e);
		}
		this.ttl = ttl;
		this.clock = clock;
	}

	/**
	 * A token that lets the assistant act for this person, at this temple, in this conversation, for
	 * the next five minutes.
	 *
	 * <p>The caller is responsible for {@code userId} and {@code tenantId} coming from the person's
	 * own verified sign-in, never from what they sent: whatever is put in here, the filter will
	 * believe — after checking the user still exists, is active and belongs to that temple.
	 */
	public String mint(UUID userId, UUID tenantId, UUID conversationId, Set<String> scopes) {
		if (userId == null || tenantId == null || conversationId == null) {
			throw new IllegalArgumentException("userId, tenantId and conversationId are all required");
		}
		if (scopes == null || scopes.isEmpty() || !KNOWN_SCOPES.containsAll(scopes)) {
			throw new IllegalArgumentException("scopes must be a non-empty subset of " + KNOWN_SCOPES);
		}

		Instant now = clock.instant();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.subject(userId.toString())
				.claim(CLAIM_TENANT, tenantId.toString())
				.claim(CLAIM_CONVERSATION, conversationId.toString())
				// Sorted so the same scopes always produce the same claim, which makes tokens and logs
				// comparable by eye. Order carries no meaning.
				.claim(CLAIM_SCOPE, String.join(" ", scopes.stream().sorted().toList()))
				.issueTime(Date.from(now))
				.expirationTime(Date.from(now.plus(ttl)))
				.build();

		SignedJWT jwt = new SignedJWT(
				new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims);
		try {
			jwt.sign(signer);
		} catch (JOSEException e) {
			// Only possible with a bad key, which the constructor already refused.
			throw new IllegalStateException("Could not sign an agent token", e);
		}
		return jwt.serialize();
	}

	/**
	 * Checks a token and returns what it claims. Says nothing about whether the person still exists
	 * or is still allowed in — that is {@link AgentTokenFilter}'s job, against our own records, on
	 * every request.
	 *
	 * @throws InvalidAgentTokenException for every way a token can be wrong, never distinguished
	 */
	public AgentPrincipal verify(String token) throws InvalidAgentTokenException {
		if (token == null || token.isBlank()) {
			throw new InvalidAgentTokenException("no token");
		}

		SignedJWT jwt;
		try {
			jwt = SignedJWT.parse(token);
		} catch (ParseException e) {
			throw new InvalidAgentTokenException("not a signed JWT");
		}

		// Checked before the signature, so a token claiming any other algorithm — "none" above all —
		// is never handed to a verifier at all.
		if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
			throw new InvalidAgentTokenException("wrong algorithm");
		}
		try {
			if (!jwt.verify(verifier)) {
				throw new InvalidAgentTokenException("bad signature");
			}
		} catch (JOSEException e) {
			throw new InvalidAgentTokenException("signature could not be checked");
		}

		JWTClaimsSet claims;
		try {
			claims = jwt.getJWTClaimsSet();
		} catch (ParseException e) {
			throw new InvalidAgentTokenException("unreadable claims");
		}

		Instant now = clock.instant();
		Date issuedAt = claims.getIssueTime();
		Date expiresAt = claims.getExpirationTime();
		if (issuedAt == null || expiresAt == null) {
			throw new InvalidAgentTokenException("missing iat or exp");
		}
		if (!now.isBefore(expiresAt.toInstant())) {
			throw new InvalidAgentTokenException("expired");
		}
		if (issuedAt.toInstant().isAfter(now.plus(ISSUED_AT_TOLERANCE))) {
			throw new InvalidAgentTokenException("issued in the future");
		}

		UUID userId = uuidClaim(claims.getSubject(), "sub");
		UUID tenantId = uuidClaim(stringClaim(claims, CLAIM_TENANT), CLAIM_TENANT);
		UUID conversationId = uuidClaim(stringClaim(claims, CLAIM_CONVERSATION), CLAIM_CONVERSATION);

		String scopeClaim = stringClaim(claims, CLAIM_SCOPE);
		if (scopeClaim == null || scopeClaim.isBlank()) {
			throw new InvalidAgentTokenException("missing scope");
		}
		Set<String> scopes = new LinkedHashSet<>(Arrays.asList(scopeClaim.trim().split("\\s+")));
		if (!KNOWN_SCOPES.containsAll(scopes)) {
			throw new InvalidAgentTokenException("unknown scope");
		}

		return new AgentPrincipal(userId, tenantId, conversationId, scopes);
	}

	private static String stringClaim(JWTClaimsSet claims, String name)
			throws InvalidAgentTokenException {
		try {
			return claims.getStringClaim(name);
		} catch (ParseException e) {
			throw new InvalidAgentTokenException("claim " + name + " is not a string");
		}
	}

	private static UUID uuidClaim(String value, String name) throws InvalidAgentTokenException {
		if (value == null) {
			throw new InvalidAgentTokenException("missing " + name);
		}
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException e) {
			throw new InvalidAgentTokenException(name + " is not a UUID");
		}
	}

	private static byte[] resolveKey(String base64Key) {
		if (base64Key == null || base64Key.isBlank()) {
			// Loud enough to be noticed in a deployed log, quiet enough not to alarm a test run.
			log.warn("kms.assistant.token.key is not set; using a random in-memory key. Agent tokens "
					+ "will only verify on the instance that minted them.");
			byte[] key = new byte[MINIMUM_KEY_BYTES];
			new SecureRandom().nextBytes(key);
			return key;
		}
		try {
			return Base64.getDecoder().decode(base64Key.trim());
		} catch (IllegalArgumentException e) {
			// The value itself is a secret, so it is never echoed into the message.
			throw new IllegalStateException("kms.assistant.token.key is not valid base64", e);
		}
	}

	/**
	 * A token that is not one we would accept. The message is for the log only and never reaches the
	 * caller; it never contains the token, which may be a live credential.
	 */
	public static class InvalidAgentTokenException extends Exception {
		public InvalidAgentTokenException(String message) {
			super(message);
		}
	}
}
