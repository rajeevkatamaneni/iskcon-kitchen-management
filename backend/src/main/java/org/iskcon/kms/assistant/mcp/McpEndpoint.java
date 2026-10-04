package org.iskcon.kms.assistant.mcp;

import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /mcp}: hands the request to the MCP transport, unchanged (E10-T6).
 *
 * <p>A controller rather than a registered servlet for the reason given on
 * {@link McpServerConfiguration}: a second servlet would break every plain-string request matcher
 * in the security configuration.
 *
 * <p>POST carries every JSON-RPC message. GET is the optional server-to-client stream, which a
 * stateless server does not offer, so it is answered 405, as the Streamable HTTP specification
 * allows, and clients fall back to POST alone. DELETE ends a session, and there is no session;
 * Spring answers it 405 because it is not mapped.
 *
 * <p><strong>The 405 is written here, not left to the transport.</strong> The transport answers GET
 * with {@code sendError(405)}, and {@code sendError} makes the container re-dispatch to
 * {@code /error}. That second dispatch is not under {@code /mcp}, so it meets the Firebase chain,
 * which has no identity for it and turns the 405 into a 401. Measured, not guessed: the transport
 * test saw 401 until this method existed. A client told 401 concludes its token is bad and stops;
 * told 405, it carries on with POST. (The transport's other {@code sendError} cases, a body over
 * 16 MB and a server shutting down, have the same shape and are rare enough to leave.)
 *
 * <p>{@code agent:read} is declared here as well as required by the agent-token filter chain, so
 * this endpoint states its own permission like every other, and does not depend on one line of a
 * filter configuration it cannot see. The tools then check the caller's own permissions (see
 * {@link InventoryTools}).
 */
@RestController
public class McpEndpoint {

	private final HttpServletStatelessServerTransport transport;

	public McpEndpoint(HttpServletStatelessServerTransport transport) {
		this.transport = transport;
	}

	@GetMapping(McpServerConfiguration.ENDPOINT)
	@PreAuthorize("hasAuthority('agent:read')")
	public void noStream(HttpServletResponse response) {
		response.setHeader("Allow", "POST");
		response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
	}

	@PostMapping(McpServerConfiguration.ENDPOINT)
	@PreAuthorize("hasAuthority('agent:read')")
	public void handle(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		transport.service(request, response);
	}
}
