package org.iskcon.kms.staff;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Create a staff profile for a KITCHEN_STAFF user (E6-S1). */
public record CreateStaffProfileRequest(
		@NotNull UUID userId,
		@Size(max = 100) String designation) {
}
