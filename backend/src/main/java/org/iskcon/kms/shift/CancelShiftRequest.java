package org.iskcon.kms.shift;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cancel a shift (E6-S2); the reason is included in the apology to affected volunteers. */
public record CancelShiftRequest(@NotBlank @Size(max = 500) String reason) {
}
