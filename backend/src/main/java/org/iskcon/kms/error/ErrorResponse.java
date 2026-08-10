package org.iskcon.kms.error;

import java.util.List;

/**
 * What the client receives when something fails. This is the entire contract — there is no
 * variant that carries a stack trace, an exception class name, or a database message.
 *
 * @param code what the user quotes to whoever is helping them, e.g. {@code KMS-4172}
 * @param message what happened, in plain language
 * @param action what they can do next
 * @param fieldErrors per-field validation messages, empty unless this is a form submission
 */
public record ErrorResponse(
		String code,
		String message,
		String action,
		List<FieldError> fieldErrors) {

	public record FieldError(String field, String message) {
	}

	public static ErrorResponse of(ErrorCode errorCode) {
		return new ErrorResponse(
				errorCode.reference(),
				errorCode.whatHappened(),
				errorCode.whatToDo(),
				List.of());
	}

	public static ErrorResponse of(ErrorCode errorCode, List<FieldError> fieldErrors) {
		return new ErrorResponse(
				errorCode.reference(),
				errorCode.whatHappened(),
				errorCode.whatToDo(),
				fieldErrors);
	}
}
