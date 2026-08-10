package org.iskcon.kms.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.user.User;
import org.iskcon.kms.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
			extractToken(request).ifPresent(this::authenticate);
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

	private void authenticate(String idToken) {
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

		Optional<User> found = userRepository.findByFirebaseUid(subject.uid());

		if (found.isEmpty()) {
			// No row for this uid yet. This may be a provisioned or invited person signing in for
			// the first time — bind their real uid to the pending account whose verified contact
			// matches. Returns empty if there is nothing to claim.
			found = pendingAccountClaim.attemptClaim(subject);
		}

		if (found.isEmpty()) {
			// Authenticated with Firebase but has no account here. Common and legitimate:
			// someone who signed up but has not been provisioned by a temple admin.
			log.debug("No application user for verified uid");
			TenantContext.clear();
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
	}
}
