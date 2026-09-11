package org.iskcon.kms.error;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.Pattern;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
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

	/**
	 * How many values are still worth printing in a sentence somebody has to read.
	 *
	 * <p>Listing what an enum accepts is the whole point of the field errors below, and it stops
	 * being help at some size: {@code AuditAction} has 108 constants in this tree today and
	 * {@code ErrorCode} has 148, and a wall of them next to a box tells a temple administrator less
	 * than one sentence would. Past this many the field is still named — which was the actual defect
	 * — and the list is left to the API documentation.
	 */
	private static final int MOST_VALUES_WORTH_LISTING = 12;

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
	 *
	 * <p><strong>It also names the field, which until T-105 it did not, and that was the sharper half
	 * of the same defect.</strong> A body Jackson cannot parse never reaches the validator at all, so
	 * the request that is <em>most</em> obviously the caller's own typo — {@code "unit": "EACH"} on a
	 * purchase order line — was answered with the same KMS-400001 as a failed constraint but with an
	 * empty field list, while every ordinary validation failure on the very same endpoint arrived
	 * naming the box to go and fix. Two different answers to the same question, and the less useful
	 * one went to the easier mistake.
	 *
	 * <p>What is added is the field and, for an enum, the values it will accept — both of which are
	 * the caller's own vocabulary and neither of which is ours. What is deliberately <em>not</em>
	 * added is Jackson's sentence: it names the Java type and the JSON pointer, and pasting it here
	 * would trade one defect for the one the paragraph above exists to prevent. {@link
	 * #nameOfFieldAt} and {@link #whatThisFieldWillAccept} read the exception's structure — its
	 * {@code getPath()} and its target type — rather than scraping its message, so there is no
	 * wording to leak and nothing to re-break when the library rephrases itself.
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleUnreadableBody(
			HttpMessageNotReadableException e, HttpServletRequest request) {

		ErrorCode code = ErrorCode.VALIDATION_FAILED;
		List<ErrorResponse.FieldError> fieldErrors = describeUnreadableValue(e);

		log.warn("{} method={} path={} fields={} reason={}",
				code.reference(), request.getMethod(), request.getRequestURI(), fieldErrors,
				e.getMostSpecificCause().toString());

		return ResponseEntity.status(code.httpStatus()).body(ErrorResponse.of(code, fieldErrors));
	}

	/**
	 * Which field of the body could not be read, and what it would have taken — or nothing at all,
	 * for the failures where no single field is to blame.
	 *
	 * <p>The empty answer is the honest one more often than it looks. A truncated body or an
	 * unescaped newline is a {@code JsonParseException} with no path in it: nothing there identifies
	 * a field, and inventing one would point somebody at a box that is perfectly fine. Only
	 * {@link MismatchedInputException} — the family Jackson raises once it knows which property it
	 * was filling — carries the {@code getPath()} this reads.
	 */
	private List<ErrorResponse.FieldError> describeUnreadableValue(Throwable thrown) {
		MismatchedInputException mismatch = firstMismatchedInput(thrown);
		if (mismatch == null) {
			return List.of();
		}

		String field = nameOfFieldAt(mismatch.getPath());
		if (field.isEmpty()) {
			return List.of();
		}
		return List.of(new ErrorResponse.FieldError(field, whatThisFieldWillAccept(mismatch.getTargetType())));
	}

	/**
	 * The first mismatch in the chain, because Spring hands us its own wrapper and not Jackson's
	 * exception.
	 *
	 * <p>Bounded rather than looped to exhaustion: a cause chain that cycles is a library bug, and
	 * hanging the request thread while an exception is being turned into a response would turn that
	 * bug into an outage. Ten links is far past anything real.
	 */
	private MismatchedInputException firstMismatchedInput(Throwable thrown) {
		Throwable cause = thrown;
		for (int depth = 0; cause != null && depth < 10; depth++) {
			if (cause instanceof MismatchedInputException mismatch) {
				return mismatch;
			}
			cause = cause.getCause();
		}
		return null;
	}

	/**
	 * The field's name as the caller wrote it — {@code lines[0].unit} — built from Jackson's own
	 * references rather than from its message.
	 *
	 * <p>Every part of this is the caller's vocabulary: {@code getFieldName()} is the JSON property,
	 * which is the name they typed, and the index is the position in the array they sent. A Java
	 * type name cannot appear here, which is the property that makes echoing it back safe at all.
	 *
	 * <p>A reference that is neither a named field nor an index is skipped rather than guessed at.
	 * That can leave the whole path empty — a failure against the root object itself — and the
	 * caller of this treats an empty path as "no field to name", which is the truth.
	 */
	private String nameOfFieldAt(List<JsonMappingException.Reference> references) {
		StringBuilder path = new StringBuilder();

		for (JsonMappingException.Reference reference : references) {
			if (reference.getFieldName() != null) {
				if (!path.isEmpty()) {
					path.append('.');
				}
				path.append(reference.getFieldName());
			}
			else if (reference.getIndex() >= 0) {
				path.append('[').append(reference.getIndex()).append(']');
			}
		}
		return path.toString();
	}

	/**
	 * What to say next to the box: the values, when there is a readable list of them, and otherwise
	 * that this one will not do.
	 *
	 * <p>Enum constants are printed as their names rather than their labels because the name is what
	 * the caller has to send — {@code KG}, not "Kg". They are the wire vocabulary, so they are not
	 * internals, and this is the one place a user-facing string is generated rather than written.
	 *
	 * <p>Anything that is not an enum gets the general sentence. A date or a decimal could be given
	 * advice of its own — "use a date like 2026-09-30" — and deliberately is not, because the format
	 * a field accepts is a fact about that field's configuration, and a sentence here that asserted
	 * it would be guessing on behalf of every date field in the product at once. Naming the field is
	 * already the whole of what was missing.
	 */
	private String whatThisFieldWillAccept(Class<?> target) {
		if (target != null && target.isEnum() && target.getEnumConstants().length <= MOST_VALUES_WORTH_LISTING) {
			String values = Arrays.stream(target.getEnumConstants())
					.map(constant -> ((Enum<?>) constant).name())
					.reduce((a, b) -> a + ", " + b)
					.orElse("");
			return "Choose one of " + values + ".";
		}
		return "That isn't a value we can use here.";
	}

	/**
	 * A value in the request that cannot be what it claims — {@code /vendor-invoices/undefined/payments},
	 * a date where a number belongs, {@code ?status=SNET}.
	 *
	 * <p>The third member of the same family as the two below, and found the same way: by a caller
	 * doing something ordinary and wrong. Answering KMS-500001 for it says the fault is ours when the
	 * request never named anything real, and sends whoever is diagnosing it into code that never ran.
	 *
	 * <p><strong>Two different things arrive here, and since T-105 they are answered differently.</strong>
	 * The argument this handler was written on — <em>"a path that identifies nothing is a 404, which
	 * is what it is"</em> — is sound for a path and unsound for a filter, and the same exception
	 * carries both.
	 *
	 * <ul>
	 *   <li>{@code /vendor-invoices/undefined/payments} <strong>names no resource</strong>. Nothing
	 *       exists at that address and nothing ever could, so KMS-400030 and a 404 is the truth.
	 *   <li>{@code /orders?status=SNET} <strong>names a real collection and one misspelt word</strong>.
	 *       The resource is fine; the field is wrong. Answering "We couldn't find what you were
	 *       looking for" sends the reader hunting for a missing order that exists — which is the
	 *       same defect T-105 exists to fix, one layer along. So a value that is not part of the
	 *       address is KMS-400001 and a 400, arriving with the field named exactly as a failed
	 *       constraint on the same endpoint would.
	 * </ul>
	 *
	 * <p>The test is {@link #isPartOfTheAddress}: whether the parameter that failed to convert is a
	 * {@code @PathVariable}. That is the whole rule, and it is deliberately a property of the
	 * parameter rather than of the URL — scoping an exception handler to a request path is the
	 * mistake the note at the bottom of this file exists to prevent. Anything that is not part of
	 * the address — a query parameter today, a typed header or cookie in principle — takes the 400,
	 * because only the address can name something that does not exist. No typed header can reach
	 * here as this is written: every {@code @RequestHeader} in the tree is a {@code String}, and a
	 * String never fails to convert.
	 *
	 * <p>Either way it now names the parameter, and lists the values when the thing it could not be
	 * is an enum — for the same reason the body handler above does.
	 *
	 * <p>Checked before this was changed: no test in the tree asserts a 404 on a query parameter.
	 * Every one of the fourteen places asserting KMS-400030 is an {@link ApplicationException}
	 * raised by service code — a withdrawn endpoint, a row another tenant's RLS policy hides, a
	 * business rule — and none of them is a type mismatch.
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleUnusableRequestValue(
			MethodArgumentTypeMismatchException e, HttpServletRequest request) {

		ErrorCode code = isPartOfTheAddress(e) ? ErrorCode.RESOURCE_NOT_FOUND : ErrorCode.VALIDATION_FAILED;
		List<ErrorResponse.FieldError> fieldErrors = e.getName() == null || e.getName().isBlank()
				? List.of()
				: List.of(new ErrorResponse.FieldError(
						e.getName(), whatThisFieldWillAccept(e.getRequiredType())));

		log.warn("{} method={} path={} parameter={} value={}",
				code.reference(), request.getMethod(), request.getRequestURI(), e.getName(), e.getValue());

		return ResponseEntity.status(code.httpStatus()).body(ErrorResponse.of(code, fieldErrors));
	}

	/**
	 * Whether the value that would not convert was part of the address itself.
	 *
	 * <p>Read off the parameter's own annotation rather than by looking at the URL, which is the
	 * distinction that matters: a handler keyed on a path is a handler that silently stops applying
	 * when somebody moves an endpoint, and re-learning that is what the closing note of this class
	 * is about.
	 *
	 * <p>A parameter we cannot see at all takes the 400 with the rest. It is the answer that is
	 * never misleading — it names the parameter the caller themselves supplied and asserts nothing
	 * about what does or does not exist — whereas a 404 guessed at in the dark tells somebody their
	 * order is missing when it is not.
	 */
	private boolean isPartOfTheAddress(MethodArgumentTypeMismatchException e) {
		MethodParameter parameter = e.getParameter();
		return parameter != null && parameter.hasParameterAnnotation(PathVariable.class);
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

	/**
	 * Everything nobody anticipated.
	 *
	 * <p><b>{@code UnexpectedRollbackException} is deliberately left to fall in here, and the next
	 * person to reach for a handler for it should read this first (T-100).</b> One did reach here —
	 * a communication retry the relay refused, whose rollback is the <em>chosen</em> behaviour
	 * described in {@code CommunicationService.retryFailed}'s own javadoc — and answering it with
	 * KMS-500001, a bare 500 and an incident id was wrong: an expected outcome must not arrive as an
	 * incident. The fix is not a handler here. {@code UnexpectedRollbackException} carries nothing
	 * about <em>which</em> transaction rolled back, so a mapping in this class is a mapping for every
	 * rollback in the application, and whatever sentence it named — "we couldn't send those copies"
	 * — would then be told to somebody whose stock adjustment failed. That is a worse defect than
	 * the one it fixes, because it is confidently wrong rather than merely unhelpful.
	 *
	 * <p>So a caller that has a real sentence for its own rollback opens its transaction with a
	 * {@code TransactionTemplate} and catches the commit itself, which is what {@code retryFailed}
	 * now does; the exception it throws in its place is an {@link ApplicationException} like any
	 * other. A rollback that reaches this method is a rollback nobody has a sentence for, and
	 * KMS-500001 is the honest answer to it.
	 */
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
