package org.iskcon.kms.ban;

import jakarta.validation.constraints.Size;

/**
 * Recording a ban at the moment of a dismissal (B9).
 *
 * <p>Carried on {@code EndEmploymentRequest} rather than being its own call, because it is a decision
 * made at the dismissal and not a separate errand run afterwards — and because the two must succeed
 * or fail together. Null when the admin did not tick the option, which is the ordinary case: not
 * every termination raises one of these, and the screen is deliberately built so that the default is
 * not to.
 *
 * <p>Both fields are mandatory (KMS-4010) and the service says so rather than letting a blank string
 * through a {@code NOT NULL} column. See {@link BanCategory} for why the reason is two things.
 *
 * @param aadhaar the signed-QR triple, where a temple has one. Nothing produces one in this build;
 *                see {@link AadhaarIdentity} for the seam and what would fill it.
 */
public record RaiseEmploymentBanRequest(

		/**
		 * Deliberately without a {@code @NotNull}: a missing category and a blank account are one
		 * failure, not two, and the service answers both with KMS-4010, which says so in words an
		 * administrator can act on. A bean-validation message here would give the two halves of the
		 * same rule two different error codes.
		 */
		BanCategory category,

		@Size(max = 4000, message = "That account is too long.")
		String account,

		AadhaarIdentity aadhaar) {
}
