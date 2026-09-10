package org.iskcon.kms.shift;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cancel a shift (E6-S2); the reason is included in the apology to affected volunteers. */
public record CancelShiftRequest(
		@NotBlank(message = "Say why the shift is being cancelled.")
		@Size(max = 500, message = "That reason is too long.")
		String reason) {
}
