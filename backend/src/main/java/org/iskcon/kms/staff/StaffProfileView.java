package org.iskcon.kms.staff;

import java.time.Instant;
import java.util.UUID;

/** A staff member's profile (E6-S1): who they are and their designation. */
public record StaffProfileView(
		UUID id,
		UUID userId,
		String fullName,
		String designation,
		boolean active,
		Instant createdAt) {
}
