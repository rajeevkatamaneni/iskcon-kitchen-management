package org.iskcon.kms.assistant.token;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.iskcon.kms.auth.RolePermissions;
import org.iskcon.kms.observability.LogContext;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.user.User;
import org.iskcon.kms.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes identity and tenant scoping for a request to {@code /mcp}, from an agent token.
 *
 * <p>The counterpart of {@code AuthenticationFilter}, and built to the same order on purpose:
 *
 * <ol>
 *   <li>verify the token — proves the API minted it, for this person, temple and conversation;
 *   <li>look the person up in our own {@code users} table;
 *   <li>reject a missing or disabled person, or one who is not at the token's temple;
 *   <li>set tenant context from <em>our</em> record, and clear it when the request ends.
 * </ol>
 *
 * <p>Step 3 is why a valid token is not enough. A token lives five minutes; a Temple Admin who
 * disables somebody expects it to take effect now, and it does — on the agent's very next tool call,
 * exactly as on the person's next click.
 *
 * <p><strong>Reading the user before the tenant is known.</strong> {@code AuthenticationFilter} has
 * to use a narrow escape ({@code TenantContext.setAuthLookupUid}) because a Firebase token names no
 * temple. An agent token does name one, and it is a claim we signed ourselves from the person's own
 * record, so the lookup simply runs scoped to that temple. RLS then does the checking for us: a user
 * at any other temple is invisible and comes back as not found. The explicit tenant comparison after
 * it is belt and braces, so the check does not rest on one mechanism alone.
 *
 * <p>The escape is deliberately <em>not</em> set here, unlike the Firebase path, which leaves it set
 * for the whole request. It widens what a read of {@code users} can see to the person's own accounts
 * at other temples (see its documentation), it exists only for sign-in and the temple switcher, and
 * the assistant needs neither. Leaving it off means a tool call sees one temple and nothing else.
 *
 * <p><strong>Authorities.</strong> The token's scopes plus the person's own permissions from
 * {@link RolePermissions}. Scopes say what the <em>agent</em> may do ({@code /mcp} requires
 * {@code agent:read}); permissions keep every existing {@code @PreAuthorize} on the services the
 * tools call meaningful, so the agent can never reach anything the person could not. The role itself
 * ({@code ROLE_…}) is not granted: endpoints declare permissions, never roles.
 *
 * <p><strong>Failures</strong> leave the request unauthenticated and the {@code /mcp} security chain
 * answers 401 with no body. There is no reference code to give: the caller is {@code claude -p}, not
 * a person, and the contract for this story adds none.
 *
 * <p>Not a Spring bean, on purpose. Spring Boot registers every {@code Filter} bean as a servlet
 * filter on every path; this one must run on {@code /mcp/**} and nowhere else, so
 * {@code SecurityConfiguration} constructs it inside the {@code /mcp} chain alone.
 */
public class AgentTokenFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(AgentTokenFilter.class);
	private static final String BEARER_PREFIX = "Bearer ";

	private final AgentTokenService tokenService;
	private final UserRepository userRepository;

	public AgentTokenFilter(AgentTokenService tokenService, UserRepository userRepository) {
		this.tokenService = tokenService;
		this.userRepository = userRepository;
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		try {
			extractToken(request).ifPresent(this::authenticate);
			chain.doFilter(request, response);
		} finally {
			// Threads are pooled. Leaving either the security context or the tenant scoping set
			// would hand the next request on this thread the previous caller's access.
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

	private void authenticate(String token) {
		AgentPrincipal claimed;
		try {
			claimed = tokenService.verify(token);
		} catch (AgentTokenService.InvalidAgentTokenException e) {
			// Logged without the token, which may be a live credential.
			log.debug("Agent token refused: {}", e.getMessage());
			return;
		}

		// Scoped to the token's own temple for the lookup — see the class comment. Cleared again on
		// every path below that does not end in a signed-in request.
		TenantContext.set(claimed.tenantId());

		Optional<User> found = userRepository.findById(claimed.userId());

		if (found.isEmpty()) {
			// Either gone, or at another temple and so hidden by RLS. Not told apart, because the
			// caller should not learn which.
			log.info("Agent token for a user not found at its temple: user={}", claimed.userId());
			TenantContext.clear();
			return;
		}

		User user = found.get();

		if (!claimed.tenantId().equals(user.getTenantId())) {
			log.warn("Agent token tenant does not match the user's temple: user={}", user.getId());
			TenantContext.clear();
			return;
		}

		if (!user.isActive()) {
			log.info("Rejected agent token for disabled user {}", user.getId());
			TenantContext.clear();
			return;
		}

		List<GrantedAuthority> authorities = new ArrayList<>();
		claimed.scopes().stream()
				.sorted()
				.map(SimpleGrantedAuthority::new)
				.forEach(authorities::add);
		RolePermissions.forRole(user.getRole()).stream()
				.map(permission -> new SimpleGrantedAuthority(permission.name()))
				.forEach(authorities::add);

		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(claimed, null, authorities));

		// Same log tagging as a person's own request, so a tool call's lines are found by the same
		// search. Cleared with the rest of the MDC when the request ends (LoggingContextFilter).
		MDC.put(LogContext.USER_ID, user.getId().toString());
		MDC.put(LogContext.TENANT_ID, user.getTenantId().toString());
	}
}
