package org.iskcon.kms.error;

import java.util.List;
import java.util.Map;

/**
 * A failure we anticipated and can describe to the person who hit it.
 *
 * <p>Carries separate payloads on purpose: the {@link ErrorCode} produces what the user sees,
 * while {@code context} carries diagnostic detail that goes only to the logs. Keeping them apart
 * is what stops an internal identifier or a vendor's raw API response from ending up on a temple's
 * screen.
 *
 * <p>{@code details} is the deliberate third thing, and it is narrow on purpose. A few refusals are
 * only useful when they say <em>which</em> — a stock shortfall that names no ingredient tells a
 * storekeeper with eight lines to check all eight by hand. Those may name what is wrong, in the
 * temple's own words about the temple's own data, and they travel. Nothing is put here by default,
 * and nothing derived from an internal identifier, a stack trace or another system's response
 * belongs in it: {@code context} exists for that and stays behind.
 */
public class ApplicationException extends RuntimeException {

	private final ErrorCode errorCode;
	private final transient Map<String, Object> context;
	private final transient List<ErrorResponse.FieldError> details;

	public ApplicationException(ErrorCode errorCode) {
		this(errorCode, Map.of(), null);
	}

	public ApplicationException(ErrorCode errorCode, Map<String, Object> context) {
		this(errorCode, context, null);
	}

	public ApplicationException(ErrorCode errorCode, Map<String, Object> context, Throwable cause) {
		this(errorCode, context, List.of(), cause);
	}

	public ApplicationException(
			ErrorCode errorCode,
			Map<String, Object> context,
			List<ErrorResponse.FieldError> details,
			Throwable cause) {

		// The message on the exception itself is for logs and stack traces, never for display.
		super(errorCode.reference() + " " + errorCode.whatHappened(), cause);
		this.errorCode = errorCode;
		this.context = context == null ? Map.of() : Map.copyOf(context);
		this.details = details == null ? List.of() : List.copyOf(details);
	}

	public ErrorCode errorCode() {
		return errorCode;
	}

	/** Diagnostic detail for the logs. Never serialised into a response. */
	public Map<String, Object> context() {
		return context;
	}

	/**
	 * The part of this refusal a person needs to read, where a code alone is not enough to act on.
	 * Empty for almost every failure, and deliberately so — see the class note.
	 */
	public List<ErrorResponse.FieldError> details() {
		return details;
	}
}
