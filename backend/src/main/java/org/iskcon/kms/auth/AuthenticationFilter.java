package org.iskcon.kms.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.observability.LogContext;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.user.User;
import org.iskcon.kms.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes identity and tenant scoping for each request.
 *
 * <p>The order matters and is the security-critical part of this class:
 *
 * <ol>
 *   <li>verify the bearer token — proves control of an email or phone number;
 *   <li>look the user up by the verified UID, using the narrow RLS escape;
 *   <li>reject unknown or disabled users, whatever Firebase says;
 *   <li>set tenant context from <em>our</em> record, never from the request.
 * </ol>
 *
 * <p>Step 3 is why authorisation is not delegated to Firebase. A token remains valid until it
 * expires; a user disabled here loses access on their next request regardless.
 *
 * <p>Requests without a token pass through unauthenticated rather than being rejected here —
 * public donation and wish-list pages legitimately have no user. Authorisation rules decide
 * what unauthenticated callers may reach.
 */
@Component
public class AuthenticationFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(AuthenticationFilter.class);
	private static final String BEARER_PREFIX = "Bearer ";

	/** Which of a person's temples this request speaks for. Validated against their memberships. */
	public static final String TEMPLE_HEADER = "X-KMS-Temple";

	private final TokenVerifier tokenVerifier;
	private final UserRepository userRepository;
	private final PendingAccountClaim pendingAccountClaim;

	public AuthenticationFilter(
			TokenVerifier tokenVerifier,
			UserRepository userRepository,
			PendingAccountClaim pendingAccountClaim) {
		this.tokenVerifier = tokenVerifier;
		this.userRepository = userRepository;
		this.pendingAccountClaim = pendingAccountClaim;
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		try {
			extractToken(request).ifPresent(token ->
					authenticate(token, request.getHeader(TEMPLE_HEADER), request.getRequestURI()));
			chain.doFilter(request, response);
		} finally {
			// Threads are pooled. Leaving either the security context or the tenant scoping
			// set would hand the next request on this thread the previous caller's access.
			TenantContext.clear();
			SecurityContextHolder.clearContext();
		}
	}

	private Optional<String> extractToken(HttpServletRequest request) {
		String header = request.getHeader("Authorization");

		if (header == null || !header.startsWith(BEARER_PREFIX)) {
			return Optional.empty();
		}
		return Optional.of(header.substring(BEARER_PREFIX.length()).trim());
	}

	private void authenticate(String idToken, String requestedTemple, String path) {
		TokenVerifier.VerifiedSubject subject;
		try {
			subject = tokenVerifier.verify(idToken);
		} catch (TokenVerifier.InvalidTokenException e) {
			// Left unauthenticated rather than throwing: the request may be headed somewhere
			// public. Logged without the token itself, which is a live credential.
			log.debug("Token verification failed: {}", e.getMessage());
			return;
		}

		// Permits reading exactly this user's row before the tenant is known — see
		// TenantContext.setAuthLookupUid and the policy in V2.
		TenantContext.setAuthLookupUid(subject.uid());

		List<User> memberships = userRepository.findAllByFirebaseUid(subject.uid());
		Optional<User> found = select(memberships, requestedTemple);

		if (found.isEmpty()) {
			// No row for this uid yet. This may be a provisioned or invited person signing in for
			// the first time — bind their real uid to the pending account whose verified contact
			// matches. Returns empty if there is nothing to claim.
			found = pendingAccountClaim.attemptClaim(subject);
		}

		if (found.isEmpty()) {
			// Verified by Firebase, but a member of no temple yet — a devotee who has just signed in
			// with Google and has not chosen where they serve. They are somebody, so they are
			// authenticated; they are nobody's member, so they hold one permission: to join a temple.
			TenantContext.clear();
			if (!isJoinFlow(path)) {
				// Everywhere else they are exactly what they were before: authenticated by Google,
				// a member of nothing, and so nobody this product can answer. 401, as ever.
				log.debug("No application user for verified uid");
				return;
			}
			log.debug("Verified uid with no membership; offering the join flow");
			AuthenticatedUser visitor = AuthenticatedUser.unaffiliated(
					subject.uid(), subject.email(), subject.phoneNumber());
			SecurityContextHolder.getContext().setAuthentication(
					new UsernamePasswordAuthenticationToken(visitor, null, visitor.getAuthorities()));
			return;
		}

		User user = found.get();

		if (!user.isActive()) {
			log.info("Rejected disabled user {}", user.getId());
			TenantContext.clear();
			return;
		}

		// Tenant comes from our record, never from the request. Null for the super-admin,
		// who is intentionally outside tenant scoping.
		if (user.getTenantId() != null) {
			TenantContext.set(user.getTenantId());
		}

		AuthenticatedUser principal = new AuthenticatedUser(user);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

		// Now that we know who this is, tag their log lines with it. Cleared with the rest of the
		// MDC when the request ends (LoggingContextFilter).
		MDC.put(LogContext.USER_ID, user.getId().toString());
		if (user.getTenantId() != null) {
			MDC.put(LogContext.TENANT_ID, user.getTenantId().toString());
		}
	}

	/**
	 * Which membership this request is speaking for. A person may belong to several temples (V52);
	 * the screen says which by echoing it back in a header, and the tenant is accepted only because
	 * it was matched against memberships that came from our own records — never taken on trust.
	 * Absent or unrecognised, the oldest membership stands, which is the temple they joined first.
	 */
	private Optional<User> select(List<User> memberships, String requestedTenant) {
		if (memberships.isEmpty()) {
			return Optional.empty();
		}
		if (requestedTenant != null && !requestedTenant.isBlank()) {
			try {
				UUID wanted = UUID.fromString(requestedTenant.trim());
				Optional<User> match = memberships.stream()
						.filter(u -> wanted.equals(u.getTenantId()))
						.findFirst();
				if (match.isPresent()) {
					return match;
				}
				log.debug("Requested temple is not one of this person's; falling back to their default");
			} catch (IllegalArgumentException e) {
				log.debug("Ignoring an unreadable temple header");
			}
		}
		return Optional.of(memberships.get(0));
	}

	/**
	 * The only two requests a verified person with no membership may make: see the temples, and join
	 * one. Whitelisted by path rather than by permission, so that the principal without a temple
	 * cannot reach an endpoint that merely asks for someone to be signed in.
	 */
	private static boolean isJoinFlow(String path) {
		return path != null
				&& (path.equals("/api/v1/temples") || path.matches("/api/v1/temples/[^/]+/join"));
	}
}
