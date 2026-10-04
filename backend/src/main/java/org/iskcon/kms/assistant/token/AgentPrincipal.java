package org.iskcon.kms.assistant.token;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * Who a request to {@code /mcp} is acting for: the Kitchen Assistant, on behalf of one signed-in
 * person, in one conversation.
 *
 * <p>Deliberately not an {@code AuthenticatedUser}. The agent is not the person, and the difference
 * has to stay visible wherever it matters — an audit entry written during a tool call should be able
 * to say "the assistant, for Rajeev" rather than just "Rajeev". What the agent may <em>do</em> is a
 * separate question, answered by the authorities on the {@code Authentication} that carries this
 * principal: its scopes plus the person's own permissions (see {@link AgentTokenFilter}), so it can
 * never do more than the person could.
 *
 * <p>{@link #tenantId} is the temple from the token, and is only ever put here after the filter has
 * checked it against the person's own {@code users} row. It is the same temple
 * {@code TenantContext} is set to for the request.
 *
 * @param userId the person the agent acts for — our {@code users.id}, not a Firebase uid
 * @param tenantId the temple the request speaks for
 * @param conversationId the assistant conversation the token was minted for
 * @param scopes what the agent may do on its own account: {@code agent:read}, {@code agent:propose}
 */
public record AgentPrincipal(UUID userId, UUID tenantId, UUID conversationId, Set<String> scopes)
		implements AuthenticatedPrincipal {

	public AgentPrincipal {
		Objects.requireNonNull(userId, "userId");
		Objects.requireNonNull(tenantId, "tenantId");
		Objects.requireNonNull(conversationId, "conversationId");
		scopes = Set.copyOf(scopes);
	}

	/**
	 * The person's id. Spring falls back to {@code toString()} for a principal that names nothing,
	 * which would put the whole record — conversation id and all — into anything that logs the name.
	 */
	@Override
	public String getName() {
		return userId.toString();
	}
}
