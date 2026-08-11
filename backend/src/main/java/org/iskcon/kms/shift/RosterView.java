package org.iskcon.kms.shift;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A shift's roster as its poster sees it (E6-S4+): who is signed up (including released spots, so
 * release activity is visible), and who is waiting. Later stories add reminder delivery status
 * (E6-S6) to the signup rows.
 */
public record RosterView(ShiftView shift, List<Signup> signups, List<Waitlister> waitlist) {

	public record Signup(
			UUID userId,
			String fullName,
			String source,
			Instant signedUpAt,
			Instant releasedAt) {
	}

	public record Waitlister(UUID userId, String fullName, int position, Instant joinedAt) {
	}
}
