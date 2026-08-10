package org.iskcon.kms.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id and puts it on the logging context, so all of a request's lines can be
 * found together. Runs first — ahead of security — so even authentication failures are logged under
 * the request's id, and echoes the id back in {@code X-Request-Id} so a caller (or a trace across
 * services) can correlate.
 *
 * <p>An incoming {@code X-Request-Id} is honoured, which is how a request's id can follow it from
 * the frontend or a gateway; otherwise one is generated. The MDC is cleared when the request ends —
 * threads are pooled, and a leaked id would mislabel the next request's logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LoggingContextFilter extends OncePerRequestFilter {

	private static final String HEADER = "X-Request-Id";

	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		String requestId = request.getHeader(HEADER);
		if (requestId == null || requestId.isBlank()) {
			requestId = UUID.randomUUID().toString();
		}

		MDC.put(LogContext.REQUEST_ID, requestId);
		response.setHeader(HEADER, requestId);

		try {
			chain.doFilter(request, response);
		} finally {
			// Clears everything this request put on the MDC, including the user/tenant the
			// authentication filter adds inside this one.
			MDC.clear();
		}
	}
}
