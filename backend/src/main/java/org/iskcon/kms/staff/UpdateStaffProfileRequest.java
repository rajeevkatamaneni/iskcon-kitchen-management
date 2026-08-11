package org.iskcon.kms.staff;

import jakarta.validation.constraints.Size;

/** Edit a staff profile's designation and active flag (E6-S1). */
public record UpdateStaffProfileRequest(@Size(max = 100) String designation, boolean active) {
}
