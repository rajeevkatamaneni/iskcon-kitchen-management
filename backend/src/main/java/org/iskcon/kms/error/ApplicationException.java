package org.iskcon.kms.error;

import java.util.Map;

/**
 * A failure we anticipated and can describe to the person who hit it.
 *
 * <p>Carries two separate payloads on purpose: the {@link ErrorCode} produces what the user
 * sees, while {@code context} carries diagnostic detail that goes only to the logs. Keeping
 * them apart is what stops an internal identifier or a vendor's raw API response from ending
 * up on a temple's screen.
 */
public class ApplicationException extends RuntimeException {

	private final ErrorCode errorCode;
	private final transient Map<String, Object> context;

	public ApplicationException(ErrorCode errorCode) {
		this(errorCode, Map.of(), null);
	}

	public ApplicationException(ErrorCode errorCode, Map<String, Object> context) {
		this(errorCode, context, null);
	}

	public ApplicationException(ErrorCode errorCode, Map<String, Object> context, Throwable cause) {
		// The message on the exception itself is for logs and stack traces, never for display.
		super(errorCode.reference() + " " + errorCode.whatHappened(), cause);
		this.errorCode = errorCode;
		this.context = context == null ? Map.of() : Map.copyOf(context);
	}

	public ErrorCode errorCode() {
		return errorCode;
	}

	/** Diagnostic detail for the logs. Never serialised into a response. */
	public Map<String, Object> context() {
		return context;
	}
}
