package org.iskcon.kms.notice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What a temple or an operator posts to every temple on the platform (E9-S1).
 *
 * <p>The limits match the CHECK constraints in V66 rather than merely resembling them, so a body
 * that is too long is refused as KMS-4001 with the field named, not as a database error the person
 * cannot act on.
 *
 * <p>Plain text, both fields. No HTML and no rich text: this is the one payload in the product that
 * one temple writes and another temple's browser renders, so markup here would be a cross-tenant
 * scripting hole dressed up as a formatting feature. A line break is all a recall has ever needed.
 */
public record RaiseNoticeRequest(

		@NotNull NoticeSeverity severity,

		@NotBlank @Size(max = 120) String subject,

		/** Long enough for batch numbers and a phone number; too short to be a newsletter. */
		@NotBlank @Size(max = 4000) String body) {
}
