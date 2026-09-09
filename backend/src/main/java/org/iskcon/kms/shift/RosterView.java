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

	/**
	 * One person on the roster.
	 *
	 * <p>{@code attended} is a {@link Boolean} rather than a {@code boolean}, and nullable on
	 * purpose (B7): null means nobody has marked this shift yet, which is a different fact from
	 * "did not come". Every reliability and hours-contributed figure built on this has to be able
	 * to tell the two apart, or a shift nobody got round to marking reads as a roster of no-shows.
	 * {@code attendanceRecordedAt} moves with it — the database carries that pairing as a CHECK.
	 *
	 * <p>{@code attendanceCorrectedAt} / {@code attendanceCorrectedByName} (T-099, finishing T-079)
	 * are null on a mark that stands as it was first given — including a first answer given late,
	 * through the correction door, to somebody a partial marking left out. That is deliberate and
	 * matches V110's own comment on the columns underneath: a first answer is not a correction of
	 * one, and only a row somebody actually changed should read as "corrected" here. Naming the
	 * corrector by name rather than id follows {@code RosterView.Broadcast#sentByName} in the same
	 * file — the screen has no reason to resolve an id, and a departed coordinator's row still says
	 * only "corrected", with no name, once the column goes {@code SET NULL}.
	 */
	public record Signup(
			UUID userId,
			String fullName,
			String source,
			Instant signedUpAt,
			Instant releasedAt,
			Boolean attended,
			Instant attendanceRecordedAt,
			Instant attendanceCorrectedAt,
			String attendanceCorrectedByName,
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
