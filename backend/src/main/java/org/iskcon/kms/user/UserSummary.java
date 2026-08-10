package org.iskcon.kms.user;

import java.time.Instant;
import java.util.UUID;

/** A user as the temple's user-management list shows them. */
public record UserSummary(
		UUID id,
		String fullName,
		String email,
		String phone,
		String role,
		String status,
		Instant createdAt) {
}
