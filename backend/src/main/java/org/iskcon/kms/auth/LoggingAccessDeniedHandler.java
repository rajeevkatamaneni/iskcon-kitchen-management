package org.iskcon.kms.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Turns an authorisation failure into a clean 403 and a log line worth reading.
 *
 * <p>The story asks for denials to record actor and attempted action, and the reason is
 * operational rather than bureaucratic: a burst of denials for one user is either someone
 * probing, or — far more likely in a temple — someone whose role was set wrong and who is now
 * quietly unable to do their seva. Neither is visible if denials are silent.
 *
 * <p>The response body deliberately says nothing about which permission was required. Telling a
 * caller exactly which permission they lack maps out the authorisation model for anyone probing
 * it; the log has that detail for whoever is meant to see it.
 */
@Component
public class LoggingAccessDeniedHandler implements AccessDeniedHandler {

	private static final Logger log = LoggerFactory.getLogger(LoggingAccessDeniedHandler.class);

	@Override
	public void handle(
			HttpServletRequest request,
			HttpServletResponse response,
			AccessDeniedException accessDeniedException)
			throws IOException {

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		String actor = "anonymous";
		String role = "none";
		String tenant = "none";

		if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
			actor = String.valueOf(user.getUserId());
			role = user.getRole().name();
			tenant = String.valueOf(user.getTenantId());
		}

		log.warn("Access denied: actor={} role={} tenant={} method={} path={}",
				actor, role, tenant, request.getMethod(), request.getRequestURI());

		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"error\":\"forbidden\"}");
	}
}
