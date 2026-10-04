package org.iskcon.kms.assistant.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.iskcon.kms.tenancy.TenantContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Carries the caller's temple and identity from the HTTP request thread to the thread a tool
 * handler actually runs on (E10-T6).
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Everything that keeps one temple out of another's data hangs off two thread-locals:
 * {@link TenantContext}, which {@code TenantAwareDataSource} reads when it hands out a connection and
 * turns into {@code app.tenant_id} for Row-Level Security, and Spring Security's
 * {@link SecurityContextHolder}, which every {@code @PreAuthorize} consults. The agent-token filter
 * (T5) sets both on the request thread, exactly as {@code AuthenticationFilter} does for a Firebase
 * token.
 *
 * <p><strong>The MCP SDK does not run a tool on that thread.</strong> Its synchronous tool handlers
 * are wrapped in {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())} — read in
 * {@code McpStatelessServerFeatures.AsyncToolSpecification.fromSync} of SDK 1.1.4 — and the servlet
 * transport then {@code block()}s the request thread until that worker answers. So by default the
 * handler runs on a Reactor worker where neither thread-local is set. The failure that produces is
 * not an error: with no {@code app.tenant_id} the RLS policy fails closed, every query returns zero
 * rows, and the model is told — politely and wrongly — that the temple holds no stock at all.
 *
 * <p>The SDK has an {@code immediateExecution(true)} switch that skips the hop, and it was
 * deliberately not relied on. Whether the handler stays on the request thread then depends on every
 * operator between the transport and the handler staying synchronous, which is a property of the
 * SDK's internals rather than of anything we wrote, and a future SDK version could change it without
 * a line of ours changing. Carrying the values across explicitly is correct on any thread.
 *
 * <h2>How</h2>
 *
 * <p>The SDK offers exactly one hook that runs on the request thread and whose output reaches the
 * handler: the transport's context extractor, which turns the servlet request into an
 * {@link McpTransportContext} that is then passed to the handler as its first argument.
 * {@link #capture} is that extractor. It reads the two thread-locals the filter set — never anything
 * from the request itself, so a header or a body can no more choose a temple here than anywhere else
 * — and {@link #run} puts them back on whatever thread the handler is on, then restores what was
 * there before.
 *
 * <p><strong>A call with no temple is refused here, not passed through.</strong> See {@link #run}.
 */
final class AgentCallScope {

	static final String TENANT_KEY = "kms.tenantId";
	static final String AUTHENTICATION_KEY = "kms.authentication";

	private AgentCallScope() {
	}

	/**
	 * The transport's context extractor. Runs on the request thread, after the security filter chain.
	 *
	 * <p>The request argument is unused on purpose: everything comes from what the verified-token
	 * filter already established.
	 */
	static McpTransportContext capture(HttpServletRequest ignored) {
		return captureCurrent();
	}

	/** {@link #capture} without a request, for the handler tests. */
	static McpTransportContext captureCurrent() {
		// HashMap rather than Map.of: either value may legitimately be absent (an unauthenticated
		// call that somehow got this far), and Map.of throws on a null.
		Map<String, Object> values = new HashMap<>();
		TenantContext.get().ifPresent(tenant -> values.put(TENANT_KEY, tenant));
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null) {
			values.put(AUTHENTICATION_KEY, authentication);
		}
		return McpTransportContext.create(values);
	}

	/**
	 * Runs {@code work} with the captured temple and identity set on the current thread, then puts
	 * back whatever the thread held before.
	 *
	 * <p><strong>No temple, no call.</strong> If the context carries no tenant, this throws
	 * {@link NoTempleException} rather than running {@code work} unscoped. Running it would not leak
	 * anything — RLS fails closed — but it would answer "nothing is in stock", which is a confident
	 * wrong answer, and a model relays a confident answer as fact.
	 *
	 * <p><strong>Restore, not clear.</strong> On a Reactor worker the previous values are empty and
	 * restoring them is a clear, which is the hygiene a pooled thread needs: a worker that kept this
	 * temple set would hand it to whatever ran there next. On the request thread itself (should the
	 * SDK ever run the handler there) the previous values are the filter's own, and clearing them
	 * would strip the request of its temple before the filter's own {@code finally} ran.
	 */
	static <T> T run(McpTransportContext context, Supplier<T> work) {
		UUID tenant = (UUID) context.get(TENANT_KEY);
		if (tenant == null) {
			throw new NoTempleException();
		}
		Authentication authentication = (Authentication) context.get(AUTHENTICATION_KEY);

		Optional<UUID> previousTenant = TenantContext.get();
		SecurityContext previousSecurity = SecurityContextHolder.getContext();
		try {
			TenantContext.set(tenant);
			SecurityContext scoped = SecurityContextHolder.createEmptyContext();
			scoped.setAuthentication(authentication);
			SecurityContextHolder.setContext(scoped);
			return work.get();
		} finally {
			if (previousTenant.isPresent()) {
				TenantContext.set(previousTenant.get());
			} else {
				TenantContext.clear();
			}
			SecurityContextHolder.setContext(previousSecurity);
		}
	}

	/** The call reached a tool with no temple behind it. See {@link #run}. */
	static final class NoTempleException extends RuntimeException {
		NoTempleException() {
			super("MCP tool call reached a handler with no tenant in its transport context");
		}
	}
}
