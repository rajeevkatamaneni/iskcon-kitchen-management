package org.iskcon.kms.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.error.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Turns an authentication failure into the same error contract every other failure uses.
 *
 * <p>It exists because {@code GlobalExceptionHandler} cannot do this job. That class is a
 * {@code @RestControllerAdvice}, so it only ever sees what is thrown inside {@code
 * DispatcherServlet}; a request refused by the security filter chain never reaches the dispatcher
 * at all. Until this class, the chain answered with a bare {@code HttpStatusEntryPoint} — a 401
 * with no body whatsoever — which meant two carefully written codes, {@code ACCOUNT_DISABLED} and
 * {@code NO_ACCOUNT_AT_TEMPLE}, existed in {@code ErrorCode} and were thrown nowhere. A disabled
 * volunteer was told nothing at all, and the web app, having nothing to read, guessed: it reported
 * every bodyless 401 as "you have no account at this temple", which for a disabled person is both
 * wrong and cruel.
 *
 * <p><strong>Why an attribute rather than an exception.</strong> The obvious implementation is for
 * {@link AuthenticationFilter} to throw, and it is wrong. Read that filter's class documentation:
 * a request with no usable credentials is deliberately allowed through unauthenticated, because it
 * may be headed somewhere public — a provider calling a webhook, somebody opening an unsubscribe
 * link from an email, the temple list a devotee is shown before they have an account. Throwing
 * would turn every one of those into a 401. So the filter records <em>why</em> it declined to
 * authenticate, on the request, and carries on. If the request then reaches a {@code permitAll}
 * endpoint this class is never invoked and nothing is emitted, which is exactly right: there was
 * no failure, only a caller who did not need to be anybody.
 *
 * <p><strong>Why the fall-back is still bodyless.</strong> An absent attribute means nothing chose
 * to explain itself — no token at all, or a token that failed verification. The second of those is
 * deliberate policy, not an oversight: {@code TokenVerifier} refuses to say why a token was
 * rejected, on the grounds that a precise reason helps an attacker more than a user. Preserving
 * today's empty 401 there keeps that decision where it was made, rather than quietly reversing it
 * from here.
 */
@Component
public class AuthenticationFailureEntryPoint implements AuthenticationEntryPoint {

	/**
	 * Where {@link AuthenticationFilter} leaves the reason it declined to authenticate a request.
	 *
	 * <p>The value is an {@link ErrorCode}. Fully-qualified so it cannot collide with an attribute
	 * set by the servlet container, Spring, or a filter somebody adds later.
	 */
	public static final String FAILURE_ATTRIBUTE =
			AuthenticationFailureEntryPoint.class.getName() + ".failure";

	private final ObjectMapper objectMapper;

	/**
	 * Takes the application's own {@code ObjectMapper} rather than building one, so that a body
	 * written from out here is byte-for-byte what the same record would have been given had it come
	 * back through the dispatcher. A second, differently-configured mapper is how two shapes of the
	 * same error start to diverge without anybody noticing.
	 */
	public AuthenticationFailureEntryPoint(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void commence(
			HttpServletRequest request,
			HttpServletResponse response,
			AuthenticationException authenticationException)
			throws IOException {

		Object recorded = request.getAttribute(FAILURE_ATTRIBUTE);

		if (!(recorded instanceof ErrorCode code)) {
			// Nobody explained themselves. Today's behaviour, unchanged: 401, no body.
			response.setStatus(HttpStatus.UNAUTHORIZED.value());
			return;
		}

		response.setStatus(code.httpStatus());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(response.getWriter(), ErrorResponse.of(code));
	}
}
