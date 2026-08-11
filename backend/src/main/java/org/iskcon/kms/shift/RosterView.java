package org.iskcon.kms.shift;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A shift's roster as its poster sees it (E6-S4+): who is signed up (including released spots, so
 * release activity is visible), and who is waiting. Later stories add reminder delivery status
 * (E6-S6) to the signup rows.
 */
public record RosterView(
		ShiftView shift, List<Signup> signups, List<Waitlister> waitlist, List<Broadcast> broadcasts) {

	public record Signup(
			UUID userId,
			String fullName,
			String source,
			Instant signedUpAt,
			Instant releasedAt,
			List<Reminder> reminders) {
	}

	public record Waitlister(UUID userId, String fullName, int position, Instant joinedAt) {
	}

	/** A reminder that was sent to a signup (E6-S6), with the channel and its delivery status. */
	public record Reminder(int offsetMinutes, String channel, String status) {
	}

	/** A one-off broadcast sent to the shift (E6-S7), with per-recipient delivery status. */
	public record Broadcast(
			String message, String sentByName, Instant createdAt, List<Recipient> recipients) {
	}

	public record Recipient(String fullName, String channel, String status) {
	}
}
