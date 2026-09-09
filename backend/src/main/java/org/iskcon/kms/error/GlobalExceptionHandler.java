package org.iskcon.kms.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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

	/**
	 * The rule every phone number in this system is held to — E.164, and therefore a leading
	 * {@code +} and a country code.
	 *
	 * <p>It is repeated here from the eight fields that declare it rather than shared with them,
	 * and that is the honest trade rather than an oversight. A constant would have to live
	 * somewhere both a request record and this handler can see, and a DTO importing the exception
	 * handler to describe its own field is worse coupling than a duplicated literal. What keeps the
	 * two in step is {@code PhoneValidationIT}, which posts a malformed number to every one of
	 * those endpoints: change the rule in a DTO and forget this line, and the carve-out silently
	 * stops applying to that field — so a test, not a compiler, is what has to notice, and one does.
	 */
	private static final String E164_PHONE_RULE = "^\\+[1-9][0-9]{7,14}$";

	@ExceptionHandler(ApplicationException.class)
	public ResponseEntity<ErrorResponse> handleApplicationException(
			ApplicationException e, HttpServletRequest request) {

		ErrorCode code = e.errorCode();
		String incidentId = newIncidentId();

		// Anticipated failures are logged at WARN with their context. The context stays here
		// and never travels to the client.
		log.warn("{} incident={} actor={} path={} context={}",
				code.reference(), incidentId, describeActor(), request.getRequestURI(), e.context(), e);

		// `details` is the exception's own narrow exception to that rule, and is empty for almost
		// every failure. Where it is not, it holds the part of the refusal a person has to read to
		// act on it — which of eight lines the store is short of — in the temple's words about the
		// temple's data. Never the context, which is for us.
		return ResponseEntity.status(code.httpStatus())
				.body(ErrorResponse.of(code, e.details()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(
			MethodArgumentNotValidException e, HttpServletRequest request) {

		List<ErrorResponse.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
				.map(fe -> new ErrorResponse.FieldError(
						fe.getField(),
						fe.getDefaultMessage() == null ? "This isn't valid." : fe.getDefaultMessage()))
				.toList();

		// The one carve-out in this method, and it is deliberately narrow: a submission that failed
		// *only* on a phone number is answered with the code written for that, because
		// KMS-400001's "Check the highlighted fields" says nothing a person can act on when the
		// thing they got wrong is a phone number, and KMS-400003 tells them exactly what is
		// missing. Anything else — including a phone number alongside any other bad field — stays
		// KMS-400001 with the whole list, because a code that names one field would be a lie about
		// the rest of the form.
		ErrorCode code = isOnlyAboutAPhoneNumber(e.getBindingResult().getAllErrors())
				? ErrorCode.INVALID_PHONE_NUMBER
				: ErrorCode.VALIDATION_FAILED;

		// Validation failures are ordinary user behaviour, not incidents. Logged at DEBUG so
		// they don't drown the signal in a solo operator's log.
		log.debug("{} path={} fields={}", code.reference(), request.getRequestURI(), fieldErrors);

		return ResponseEntity.status(code.httpStatus()).body(ErrorResponse.of(code, fieldErrors));
	}

	/**
	 * Whether every single thing wrong with this submission was a phone number in the wrong shape.
	 *
	 * <p>Note which of two possible rules this is. It is not "did a phone number fail" — that would
	 * hand KMS-400003 to a form with four other empty boxes and hide them. It is "was the phone
	 * number the *only* thing that failed", which is the only case where naming one field tells the
	 * whole truth. A staff hire failing on both of its two numbers still qualifies, and should:
	 * every error is the same kind, and the field list travels with the response to say which.
	 *
	 * <p>Deliberately over {@code getAllErrors()} rather than {@code getFieldErrors()}. An
	 * object-level constraint failing at the same time is a second thing wrong with the form, and
	 * it does not appear in the field errors — testing only those would let it be silently dropped
	 * behind a code that claims the phone number was the problem.
	 */
	private boolean isOnlyAboutAPhoneNumber(List<ObjectError> errors) {
		return !errors.isEmpty() && errors.stream().allMatch(this::isMalformedPhoneNumber);
	}

	/**
	 * Whether one error is a violation of the phone rule specifically.
	 *
	 * <p>Keyed on the constraint that failed, never on the field's name, and both halves of that
	 * matter. Matching names would catch {@code @NotBlank} on an empty phone box and answer "that
	 * phone number isn't in a format we can use" to somebody who typed nothing at all — a missing
	 * number is not a malformed one, and "Enter a phone number." is already the right words for it.
	 * Matching the regexp as well as the annotation is what keeps the other {@code @Pattern} fields
	 * out: a temple's slug and its three-letter currency are pattern-checked too, and neither wants
	 * to be told about country codes.
	 *
	 * <p>It follows, and is intended, that a phone field with no {@code @Pattern} at all cannot
	 * reach this code — which is exactly right for a kitchen's contact number, where an internal
	 * extension is a legitimate answer and "include the country code" would be false advice.
	 */
	private boolean isMalformedPhoneNumber(ObjectError error) {
		if (!error.contains(ConstraintViolation.class)) {
			return false;
		}
		ConstraintViolation<?> violation = error.unwrap(ConstraintViolation.class);
		return violation.getConstraintDescriptor().getAnnotation() instanceof Pattern pattern
				&& E164_PHONE_RULE.equals(pattern.regexp());
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
	 * <p>Without this it falls to {@link #handleUnexpected} and is reported as KMS-500001, "Something
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

	/**
	 * A body we cannot read at all — malformed JSON, a number where a date belongs, an enum value
	 * that is not one.
	 *
	 * <p>Without this it falls to {@link #handleUnexpected} and is answered KMS-500001, "Something went
	 * wrong at our end" — which is untrue and expensive: it tells whoever is looking that the fault is
	 * ours and sends them into code that never ran. Found on 2026-08-19 by a test whose own JSON had
	 * an unescaped newline in it, which is exactly how a real caller would find it.
	 *
	 * <p>The reason goes to the log, not to the screen. "Cannot deserialize value of type
	 * `CommunicationCategory`" is internals leaking.
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleUnreadableBody(
			HttpMessageNotReadableException e, HttpServletRequest request) {

		ErrorCode code = ErrorCode.VALIDATION_FAILED;
		log.warn("{} method={} path={} reason={}",
				code.reference(), request.getMethod(), request.getRequestURI(), e.getMostSpecificCause().toString());
		return ResponseEntity.status(code.httpStatus()).body(ErrorResponse.of(code));
	}

	/**
	 * An address that cannot be what it claims — {@code /vendor-invoices/undefined/payments}, a date
	 * where a number belongs.
	 *
	 * <p>The third member of the same family as the two below, and found the same way: by a caller
	 * doing something ordinary and wrong. Answering KMS-500001 for it says the fault is ours when the
	 * request never named anything real, and sends whoever is diagnosing it into code that never ran.
	 * A path that identifies nothing is a 404 — which is what it is.
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleUnusablePathValue(
			MethodArgumentTypeMismatchException e, HttpServletRequest request) {

		ErrorCode code = ErrorCode.RESOURCE_NOT_FOUND;
		log.warn("{} method={} path={} parameter={} value={}",
				code.reference(), request.getMethod(), request.getRequestURI(), e.getName(), e.getValue());
		return ResponseEntity.status(code.httpStatus()).body(ErrorResponse.of(code));
	}

	/**
	 * The right address, the wrong verb — a POST to a path that only answers GET.
	 *
	 * <p>Exactly the same reasoning as {@link #handleNoSuchPath} above, and found the same way: when
	 * {@code POST /api/v1/users} was withdrawn (E1-S12, E6-S8) it started answering KMS-500001, which
	 * says the fault is ours and sends the reader hunting through code that never ran. It is a
	 * KMS-400030 too: what they asked for is not there.
	 */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleWrongMethod(
			HttpRequestMethodNotSupportedException e, HttpServletRequest request) {

		ErrorCode code = ErrorCode.RESOURCE_NOT_FOUND;
		log.warn("{} method={} path={} supported={}",
				code.reference(), request.getMethod(), request.getRequestURI(), e.getSupportedHttpMethods());
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
