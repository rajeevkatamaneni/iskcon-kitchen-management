package org.iskcon.kms.donation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Striking a gift that was recorded wrongly (T-012) — entered twice, or against the wrong donor.
 *
 * <p>The reason is the whole of the request, and it is required. Every other correction in this
 * product can be understood from what it did; this one cannot. It removes money from the figures the
 * temple reports under 80G, and six months later the only account of why that happened is the
 * sentence typed here. {@code @NotBlank} rather than {@code @NotNull} because a space bar is not a
 * reason, and the same rule is enforced again by a CHECK on the column (V104) so no later write path
 * can put a blank one in behind this.
 */
public record VoidDonationRequest(
		@NotBlank(message = "Say why this gift is being struck out.")
		@Size(max = 500, message = "That reason is too long.")
		String reason) {
}
