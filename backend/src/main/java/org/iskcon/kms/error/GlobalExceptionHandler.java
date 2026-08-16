package org.iskcon.kms.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The single place an exception becomes a response.
 *
 * <p>Two rules, and everything else follows from them: nothing technical reaches the user, and
 * nothing reaches the user without a reference code. A screenshot of any error in this system
 * should be enough for support to find the exact log entry.
 *
 * <p>Each failure is also given an {@code incidentId} — a one-off identifier for this specific
 * occurrence, distinct from the error code which names the *kind* of failure. The code tells
 * support what went wrong; the incident id tells them precisely when, and to whom.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApplicationException.class)
	public ResponseEntity<ErrorResponse> handleApplicationException(
			ApplicationException e, HttpServletRequest request) {

		ErrorCode code = e.errorCode();
		String incidentId = newIncidentId();

		// Anticipated failures are logged at WARN with their context. The context stays here
		// and never travels to the client.
		log.warn("{} incident={} actor={} path={} context={}",
				code.reference(), incidentId, describeActor(), request.getRequestURI(), e.context(), e);

		return ResponseEntity.status(code.httpStatus()).body(ErrorResponse.of(code));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(
			MethodArgumentNotValidException e, HttpServletRequest request) {

		List<ErrorResponse.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
				.map(fe -> new ErrorResponse.FieldError(
						fe.getField(),
						fe.getDefaultMessage() == null ? "This isn't valid." : fe.getDefaultMessage()))
				.toList();

		ErrorCode code = ErrorCode.VALIDATION_FAILED;

		// Validation failures are ordinary user behaviour, not incidents. Logged at DEBUG so
		// they don't drown the signal in a solo operator's log.
		log.debug("{} path={} fields={}", code.reference(), request.getRequestURI(), fieldErrors);

		return ResponseEntity.status(code.httpStatus()).body(ErrorResponse.of(code, fieldErrors));
	}

	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ErrorResponse> handleAccessDenied(
			AccessDeniedException e, HttpServletRequest request) {

		ErrorCode code = ErrorCode.NOT_PERMITTED;

		log.warn("{} actor={} method={} path={}",
				code.reference(), describeActor(), request.getMethod(), request.getRequestURI());

		// Deliberately says nothing about which permission was required — that maps out the
		// authorisation model for anyone probing it.
		return ResponseEntity.status(code.httpStatus()).body(ErrorResponse.of(code));
	}

	/**
	 * A path no controller claims.
	 *
	 * <p>Without this it falls to {@link #handleUnexpected} and is reported as KMS-5001, "Something
	 * went wrong at our end" — which sends whoever is diagnosing it looking for a bug in code that
	 * was never reached. A wrong address is not an internal failure, and saying so cost real time
	 * once: a screen deployed ahead of its endpoints looked like a server fault rather than a
	 * missing route.
	 */
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleNoSuchPath(
			NoResourceFoundException e, HttpServletRequest request) {

		ErrorCode code = ErrorCode.RESOURCE_NOT_FOUND;
		log.warn("{} method={} path={}", code.reference(), request.getMethod(), request.getRequestURI());
		return ResponseEntity.status(code.httpStatus()).body(ErrorResponse.of(code));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
		ErrorCode code = ErrorCode.UNEXPECTED_FAILURE;
		String incidentId = newIncidentId();

		// Anything reaching here is a bug. Full stack trace to the log, nothing but a code to
		// the user.
		log.error("{} incident={} actor={} path={}",
				code.reference(), incidentId, describeActor(), request.getRequestURI(), e);

		return ResponseEntity.status(code.httpStatus()).body(ErrorResponse.of(code));
	}

	/**
	 * Identifies this one occurrence. Placed in the logging context so every line emitted while
	 * handling the failure carries it, which is what makes a log search from a single reference
	 * actually work.
	 */
	private String newIncidentId() {
		String incidentId = UUID.randomUUID().toString().substring(0, 8);
		MDC.put("incidentId", incidentId);
		return incidentId;
	}

	private String describeActor() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
			return user.getUserId() + "/" + user.getRole();
		}
		return "anonymous";
	}
}
