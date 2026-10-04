package org.iskcon.kms.assistant.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.user.User;
import org.iskcon.kms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The filter's own checks, with the database mocked away.
 *
 * <p>One case here cannot be reached through a real database, and that is why this class exists
 * beside {@code AgentTokenFilterIT}: a user row that comes back at a <em>different</em> temple from
 * the token's. Under RLS the lookup is scoped to the token's temple, so such a row is simply
 * invisible. The filter compares the two anyway, so the check does not rest on RLS alone, and only a
 * mock can hand it a row RLS would have hidden.
 */
class AgentTokenFilterTest {

	private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

	private final AgentTokenService tokens =
			new AgentTokenService(KEY, Duration.ofMinutes(5), Clock.systemUTC());
	private final UserRepository users = mock(UserRepository.class);
	private final AgentTokenFilter filter = new AgentTokenFilter(tokens, users);

	private final UUID userId = UUID.randomUUID();
	private final UUID tenantId = UUID.randomUUID();

	@AfterEach
	void clearThreadLocals() {
		TenantContext.clear();
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("a user returned at another temple than the token's is refused, and the tenant is cleared")
	void tenantMismatch() throws Exception {
		User elsewhere = user(UUID.randomUUID(), User.Status.ACTIVE);
		when(users.findById(userId)).thenReturn(Optional.of(elsewhere));

		Seen seen = run(token());

		assertThat(seen.authentication).isNull();
		assertThat(seen.tenant).as("no tenant may survive a refused token").isEmpty();
	}

	@Test
	@DisplayName("an active user at the token's temple is signed in, scoped to that temple")
	void accepted() throws Exception {
		User here = user(tenantId, User.Status.ACTIVE);
		when(users.findById(userId)).thenReturn(Optional.of(here));

		Seen seen = run(token());

		assertThat(seen.authentication).isNotNull();
		assertThat(seen.authentication.getPrincipal()).isInstanceOf(AgentPrincipal.class);
		assertThat(seen.tenant).contains(tenantId);
	}

	@Test
	@DisplayName("thread-local tenant and security context are cleared once the request ends")
	void clearedAfterwards() throws Exception {
		User here = user(tenantId, User.Status.ACTIVE);
		when(users.findById(userId)).thenReturn(Optional.of(here));

		run(token());

		assertThat(TenantContext.get()).isEmpty();
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	@DisplayName("a refused token never reaches the database")
	void refusedTokenSkipsLookup() throws Exception {
		Seen seen = run("not-a-jwt");

		assertThat(seen.authentication).isNull();
		verify(users, never()).findById(ArgumentMatchers.any());
	}

	// ---------------------------------------------------------------------

	private String token() {
		return tokens.mint(userId, tenantId, UUID.randomUUID(), Set.of(AgentTokenService.SCOPE_READ));
	}

	private User user(UUID tenant, User.Status status) {
		User user = mock(User.class);
		when(user.getId()).thenReturn(userId);
		when(user.getTenantId()).thenReturn(tenant);
		when(user.getRole()).thenReturn(User.Role.KITCHEN_MANAGER);
		when(user.isActive()).thenReturn(status == User.Status.ACTIVE);
		return user;
	}

	private record Seen(Authentication authentication, Optional<UUID> tenant) {
	}

	/** Runs the filter and records what the rest of the chain would have seen. */
	private Seen run(String token) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
		request.addHeader("Authorization", "Bearer " + token);
		AtomicReference<Seen> seen = new AtomicReference<>();
		FilterChain chain = (req, res) -> seen.set(new Seen(
				SecurityContextHolder.getContext().getAuthentication(), TenantContext.get()));

		filter.doFilter(request, new MockHttpServletResponse(), chain);
		return seen.get();
	}
}
