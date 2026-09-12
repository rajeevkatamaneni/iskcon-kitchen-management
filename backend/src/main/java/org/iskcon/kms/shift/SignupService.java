package org.iskcon.kms.shift;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.notification.NotificationRecipient;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.notification.NotificationTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.iskcon.kms.tenancy.TempleClock;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Volunteer signup for shifts (E6-S3), and — added by later stories — release (E6-S4), the
 * waitlist (E6-S5), and attendance with the coordinator's release (B7).
 *
 * <p>The capacity claim is made safe under concurrency by locking the shift row
 * ({@code SELECT … FOR UPDATE}) at the start of the signup transaction: every signup, release, and
 * promotion for a shift serialises on that one row, so two simultaneous signups for the last spot
 * can never both succeed. An overlapping-time signup is allowed but flagged — real families share
 * duties, so it warns rather than blocks.
 *
 * <p><strong>Since T-146 a shift may run through midnight, and that divides the clock arithmetic in
 * this class in two.</strong> Everything here that asks <em>has this shift begun?</em> — the signup
 * and waitlist guard, both release guards, and the two attendance gates — is anchored on the
 * shift's <em>start</em>, which is {@code (shift_date, start_time)} whatever the end does, and so
 * was already right. They go through {@link ShiftWindow#startsAt} now rather than building the
 * instant inline, so that a reader finds one statement of what a shift's start is instead of five
 * identical ones and a question about whether the end works the same way. The one thing that reads
 * the <em>end</em> is {@link #overlaps}, and it was wrong; see its own note.
 */
@Service
public class SignupService {

	private final TempleClock clock;

	private static final Logger log = LoggerFactory.getLogger(SignupService.class);

	private final JdbcTemplate jdbc;
	private final NotificationService notificationService;
	private final ShiftReminderScheduler reminderScheduler;
	private final AuditService auditService;

	public SignupService(JdbcTemplate jdbc, NotificationService notificationService,
			ShiftReminderScheduler reminderScheduler, TempleClock clock, AuditService auditService) {
		this.clock = clock;
		this.jdbc = jdbc;
		this.notificationService = notificationService;
		this.reminderScheduler = reminderScheduler;
		this.auditService = auditService;
	}

	/** Claims a spot on a shift for a volunteer. Throws {@link ErrorCode#SHIFT_FULL} if none is free. */
	@Transactional
	public SignupResult signUp(UUID volunteerUserId, UUID shiftId) {
		LockedShift shift = lockShift(shiftId);
		guardOpenAndFuture(shift);

		Integer active = jdbc.queryForObject("""
				SELECT count(*) FROM shift_signups
				WHERE shift_id = ? AND volunteer_user_id = ? AND released_at IS NULL
				""", Integer.class, shiftId, volunteerUserId);
		if (active != null && active > 0) {
			throw new ApplicationException(ErrorCode.ALREADY_SIGNED_UP, Map.of("shiftId", shiftId));
		}
		if (signedUpCount(shiftId) >= shift.capacity()) {
			throw new ApplicationException(ErrorCode.SHIFT_FULL, Map.of("shiftId", shiftId));
		}

		UUID signupId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO shift_signups (id, tenant_id, shift_id, volunteer_user_id, source)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, 'SIGNUP')
				""", signupId, shiftId, volunteerUserId);
		reminderScheduler.scheduleForSignup(signupId);

		return new SignupResult(signupId, overlaps(volunteerUserId, shiftId, shift));
	}

	/**
	 * Releases a volunteer's spot (E6-S4) and promotes the head of the waitlist into it (E6-S5), all
	 * under the same shift-row lock as signup so nothing races. Allowed until the shift starts.
	 * Returns the user ids promoted (0 or 1 here), for the caller to notify after commit.
	 *
	 * <p>The volunteer's own release. It is reached only from {@code VolunteerShiftController}, which
	 * passes {@code actor.getUserId()} and nothing else — that scoping is the whole of its security,
	 * and a volunteer must never be able to take somebody else off a roster through it. The
	 * coordinator's equivalent is {@link #releaseVolunteer}, a separate endpoint behind a separate
	 * permission, and the two are deliberately not one method with a parameter.
	 */
	@Transactional
	public List<UUID> release(UUID volunteerUserId, UUID shiftId) {
		return releaseSignup(shiftId, volunteerUserId);
	}

	/**
	 * The coordinator taking a <em>named</em> volunteer off a roster (B7), behind
	 * {@code MANAGE_VOLUNTEER_SHIFTS}. Until B7 this could not be done at all: the only release in
	 * the system acted on the caller's own id, so a coordinator faced with a volunteer who had
	 * stopped answering had no way to free the spot — and no way to let the waitlist have it.
	 *
	 * <p>Identical in effect to a volunteer's own release in everything the roster does: the spot is
	 * freed, the waitlist head is promoted into it, the release shows on the roster with its time,
	 * and the pending reminders for that signup are cancelled. What differs is who may ask, which is
	 * settled at the controller by the permission, and the argument that names the person — spelled
	 * out here rather than left to the argument order of {@link #release}, where
	 * {@code volunteerUserId} first and {@code shiftId} second is a trap worth not laying.
	 *
	 * <p><strong>And, since T-080, it has to say why.</strong> Two fields, doing different jobs. The
	 * structured {@code reason} is safe to show and is what the removed volunteer is told; the
	 * {@code internalNote} is mandatory, stays inside the temple, and reaches the audit trail and the
	 * roster. Before this, the waitlisted volunteer promoted into the freed place got a cheerful
	 * message and the person who lost the shift got silence — so the removal now sends
	 * {@link NotificationTemplate#REMOVED_FROM_SHIFT}, and {@code WAITLIST_PROMOTED} is untouched.
	 *
	 * <p>The started-shift guard is kept, deliberately. Releasing somebody off a shift that has
	 * already run would rewrite what the roster said at the time it mattered, and attendance (B7) is
	 * the right way to record that a person on the roster did not come.
	 *
	 * @return what was actually stored, for the caller to notify on after the transaction commits
	 */
	@Transactional
	public Removal releaseVolunteer(AuthenticatedUser actor, UUID shiftId, UUID volunteerUserId,
			RemoveVolunteerRequest request) {
		LockedShift shift = lockShift(shiftId);
		LocalDateTime start = ShiftWindow.startsAt(shift.shiftDate(), shift.startTime());
		if (!start.isAfter(LocalDateTime.now(clock.zone()))) {
			throw new ApplicationException(ErrorCode.SHIFT_ALREADY_STARTED, Map.of("shiftId", shiftId));
		}

		// The volunteer's name, read while they are still actively on the roster, for the audit
		// entry's before-state. Doubles as the "is this person actually on this shift" check, so the
		// refusal below names the right failure rather than falling out of an UPDATE touching no rows.
		List<String> names = jdbc.queryForList("""
				SELECT u.full_name
				FROM shift_signups ss JOIN users u ON u.id = ss.volunteer_user_id
				WHERE ss.shift_id = ? AND ss.volunteer_user_id = ? AND ss.released_at IS NULL
				""", String.class, shiftId, volunteerUserId);
		if (names.isEmpty()) {
			throw new ApplicationException(ErrorCode.NOT_ON_SHIFT,
					Map.of("shiftId", shiftId, "volunteerUserId", volunteerUserId));
		}
		String volunteerName = names.get(0);

		List<UUID> releasedIds = jdbc.query("""
				UPDATE shift_signups
				SET released_at = now(), released_reason = ?, released_note = ?
				WHERE shift_id = ? AND volunteer_user_id = ? AND released_at IS NULL
				RETURNING id
				""", (rs, n) -> rs.getObject("id", UUID.class),
				request.reason().name(), request.internalNote(), shiftId, volunteerUserId);
		if (releasedIds.isEmpty()) {
			// Unreachable while the shift row is locked above — every release, signup and promotion
			// for this shift serialises on it — and kept anyway, because the alternative to a named
			// refusal here is a silent success that freed nothing.
			throw new ApplicationException(ErrorCode.NOT_ON_SHIFT,
					Map.of("shiftId", shiftId, "volunteerUserId", volunteerUserId));
		}

		// Read back from the row rather than reusing the request, because an audit trail must record
		// what was STORED and not what was asked for. It is a separate SELECT and not the UPDATE's
		// own RETURNING on purpose: RETURNING would be near enough here, and the point of writing it
		// this way is that it stays right if a default, a trigger or a later constraint ever changes
		// what landing in this row means. One extra query on an act a coordinator performs by hand.
		Map<String, Object> stored = jdbc.queryForMap("""
				SELECT released_at, released_reason, released_note FROM shift_signups WHERE id = ?
				""", releasedIds.get(0));

		// The internal note goes in the `reason` argument — audit_events' own human-context column —
		// rather than into the after-state, because that is what it is: the coordinator's account of
		// why, in their own words. The after-state carries the structured answer, which is the part
		// that was also sent, so a reader can see at a glance which half of the pair the volunteer
		// got. The entity is the shift, matching ATTENDANCE_CORRECTED, and the volunteer is named in
		// the states themselves so the entry reads without resolving anybody's id.
		//
		// Every value here comes from `stored`, read back from the row above, and not from `request`.
		// An audit trail records what was stored and not what was asked for, and this is the one
		// place in this method where the difference could go unnoticed.
		//
		// The before-state says the plain thing the after-state contradicts: this person was on the
		// roster. It reads as a sentence beside the after-state rather than as a field somebody has
		// to interpret, which is the same choice ATTENDANCE_CORRECTED makes with "not marked".
		auditService.record(actor, AuditAction.VOLUNTEER_REMOVED_FROM_SHIFT, AuditEntityType.SHIFT,
				shiftId,
				Map.of("volunteer", volunteerName, "onRoster", "true"),
				Map.of("volunteer", volunteerName,
						"reason", String.valueOf(stored.get("released_reason")),
						"releasedAt", String.valueOf(stored.get("released_at"))),
				String.valueOf(stored.get("released_note")));

		reminderScheduler.cancelForSignup(shiftId, releasedIds.get(0));
		return new Removal(
				RemoveVolunteerRequest.Reason.valueOf(String.valueOf(stored.get("released_reason"))),
				promoteWithinLock(shiftId, shift));
	}

	/**
	 * What a coordinator's removal actually did, handed back for the caller to send on after commit.
	 *
	 * <p>{@code reason} is re-read from the stored row rather than echoed from the request, for the
	 * same reason the audit entry is: the volunteer is told what the roster now says, not what
	 * somebody asked it to say. {@code promoted} is 0 or 1 user id and is the existing behaviour
	 * unchanged — the waitlist message is the one thing T-080 was explicitly told not to touch.
	 */
	public record Removal(RemoveVolunteerRequest.Reason reason, List<UUID> promoted) {
	}

	/**
	 * Marks who turned up to a shift (B7), for the whole roster at once.
	 *
	 * <p>Under the same shift-row lock as signup and release, so a marking cannot interleave with a
	 * signup that would add an unmarked row behind it, and two coordinators marking at once cannot
	 * both pass the already-recorded check.
	 *
	 * <p>Marking is once per shift. A second blanket marking is {@link ErrorCode#ATTENDANCE_ALREADY_RECORDED}
	 * rather than an overwrite: attendance feeds every reliability and hours-contributed figure
	 * downstream, and a fresh sweep of the list days later would silently replace a considered answer
	 * with a remembered one.
	 *
	 * <p><strong>That refusal stands after T-079, and is now a refusal with somewhere to go.</strong>
	 * What changed is not this door but the existence of another one: {@link #correctAttendance}
	 * changes <em>one named person's</em> mark, audited, and is how a wrong answer is put right. The
	 * two are deliberately not the same call. A blanket re-save is a screen full of ticks pressed in
	 * one motion, which is exactly the act that should not be able to overwrite a considered answer;
	 * a correction names the volunteer and the answer, so it cannot be done by accident to eleven
	 * people at once.
	 *
	 * <p>A mark naming somebody who is not actively on this roster — never signed up, or released —
	 * is refused as {@link ErrorCode#NOT_ON_SHIFT} rather than ignored, because the alternative is a
	 * coordinator watching a name they marked simply not appear.
	 *
	 * <p>A shift that has not started yet cannot be marked at all (T-085). Attendance is a record of
	 * what happened, and nothing has happened yet: a coordinator who opens tomorrow's roster and
	 * saves records the whole crew as having come to a shift nobody has worked, and that figure
	 * feeds reliability and hours-contributed from the moment it lands. T-079 makes it undoable
	 * rather than permanent, which is a smaller thing than it sounds — undoing it is eleven separate
	 * corrections by somebody who has first noticed, and the record is wrong until they do. So the
	 * guard stays exactly where it is, and {@link #correctAttendance} carries it too. It is the
	 * mirror of the {@link ErrorCode#SHIFT_ALREADY_STARTED} guard on {@link #releaseSignup}, in the
	 * other direction and off the same clock, and the two together say the plain thing: a shift is
	 * released from before it begins and marked after it has run.
	 */
	@Transactional
	public void recordAttendance(UUID shiftId, List<RecordAttendanceRequest.Mark> marks) {
		LockedShift shift = lockShift(shiftId); // serialise against a concurrent signup, release or marking

		LocalDateTime start = ShiftWindow.startsAt(shift.shiftDate(), shift.startTime());
		if (start.isAfter(LocalDateTime.now(clock.zone()))) {
			throw new ApplicationException(ErrorCode.SHIFT_NOT_STARTED, Map.of("shiftId", shiftId));
		}

		Set<UUID> seen = new java.util.HashSet<>();
		for (RecordAttendanceRequest.Mark mark : marks) {
			if (!seen.add(mark.userId())) {
				// The same person marked twice in one payload is a caller contradicting itself, and
				// last-one-wins would resolve it silently into whichever order the list happened to be in.
				throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
						Map.of("field", "marks", "volunteerUserId", mark.userId()));
			}
		}

		Integer alreadyMarked = jdbc.queryForObject("""
				SELECT count(*) FROM shift_signups
				WHERE shift_id = ? AND attendance_recorded_at IS NOT NULL
				""", Integer.class, shiftId);
		if (alreadyMarked != null && alreadyMarked > 0) {
			throw new ApplicationException(ErrorCode.ATTENDANCE_ALREADY_RECORDED, Map.of("shiftId", shiftId));
		}

		for (RecordAttendanceRequest.Mark mark : marks) {
			int updated = jdbc.update("""
					UPDATE shift_signups SET attended = ?, attendance_recorded_at = now()
					WHERE shift_id = ? AND volunteer_user_id = ? AND released_at IS NULL
					""", mark.attended(), shiftId, mark.userId());
			if (updated == 0) {
				throw new ApplicationException(ErrorCode.NOT_ON_SHIFT,
						Map.of("shiftId", shiftId, "volunteerUserId", mark.userId()));
			}
		}
	}

	/**
	 * Changes one named volunteer's attendance mark (T-079), and files what it was and what it now
	 * says on the temple's audit trail.
	 *
	 * <p><strong>A plain edit with an audit entry, and explicitly not T-007's machinery.</strong>
	 * Rajeev's ruling of 2026-09-08 settles that, and the reason is physical: a corrected meal
	 * compensates its stock movements because real goods left the store on the strength of the
	 * number and the ledger is append-only. An attendance mark moves nothing. The reliability and
	 * hours-contributed figures it exists to make possible are computed on demand from this column
	 * and stored nowhere, so there is no draw to reverse and no derived figure to re-draw — the
	 * corrected mark simply is the answer, from the next read onwards.
	 *
	 * <p><strong>A separate call from {@link #recordAttendance}, rather than that one behaving
	 * differently the second time.</strong> The existing refusal is load-bearing and is kept: the
	 * blanket path is a screenful of ticks committed in one press, and letting a second press
	 * overwrite the first would mean a coordinator opening an already-marked roster days later and
	 * saving out of habit replaces eleven considered answers with eleven remembered ones — silently,
	 * because every tick starts ticked. This path cannot do that. It names one volunteer and one
	 * answer, so the smallest thing it can get wrong is one person, and every use of it is a
	 * deliberate statement about somebody rather than a re-sweep of a list.
	 *
	 * <p><strong>It is also how a partial marking is finished.</strong> A {@code marks} list that
	 * leaves people out leaves them unmarked, and any mark at all then makes a second blanket
	 * marking {@link ErrorCode#ATTENDANCE_ALREADY_RECORDED} — which, before this existed, meant the
	 * omitted volunteers could never be marked by anybody. So this path accepts a signup that
	 * carries no mark yet as readily as one that carries the wrong mark. The two cases differ in
	 * what they write, not in whether they are allowed: a first answer takes
	 * {@code attendance_recorded_at} like any other first answer and leaves the correction columns
	 * null, because there was nothing there to correct. That distinction is worth keeping honest —
	 * counting corrections is only meaningful if a correction means an answer was changed.
	 *
	 * <p>Under the same shift-row lock as every other write here, and behind T-085's guard as well:
	 * a shift that has not run cannot be marked through this door either, or the guard would have a
	 * hole in it one call wide.
	 *
	 * <p>Correctable any number of times, unlike a corrected meal ({@code MEAL_ALREADY_CORRECTED}).
	 * That refusal exists because a second correction would compensate already-compensated movements
	 * and draw the store down twice for food cooked once. Nothing here can be done twice to any ill
	 * effect — the second correction overwrites a boolean — and refusing one would leave a
	 * coordinator who mis-corrected in exactly the trap this task exists to remove.
	 */
	@Transactional
	public void correctAttendance(AuthenticatedUser actor, UUID shiftId, UUID volunteerUserId, boolean attended) {
		LockedShift shift = lockShift(shiftId); // serialise against a concurrent marking, signup or release

		LocalDateTime start = ShiftWindow.startsAt(shift.shiftDate(), shift.startTime());
		if (start.isAfter(LocalDateTime.now(clock.zone()))) {
			throw new ApplicationException(ErrorCode.SHIFT_NOT_STARTED, Map.of("shiftId", shiftId));
		}

		// The volunteer's own row, and only while they are actively on the roster — a released spot
		// is not a person whose attendance there is anything to say about, which is the same rule the
		// blanket path's UPDATE applies through its `released_at IS NULL` clause.
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT ss.id, ss.attended, ss.attendance_corrected_at, u.full_name
				FROM shift_signups ss JOIN users u ON u.id = ss.volunteer_user_id
				WHERE ss.shift_id = ? AND ss.volunteer_user_id = ? AND ss.released_at IS NULL
				""", shiftId, volunteerUserId);
		if (rows.isEmpty()) {
			throw new ApplicationException(ErrorCode.NOT_ON_SHIFT,
					Map.of("shiftId", shiftId, "volunteerUserId", volunteerUserId));
		}
		Map<String, Object> row = rows.get(0);
		UUID signupId = (UUID) row.get("id");
		Boolean was = (Boolean) row.get("attended");
		String fullName = (String) row.get("full_name");

		// Asking for the answer the row already gives is not a correction, and filing "came → came"
		// on the audit trail would be noise in the one log that has to stay worth reading. It is a
		// state the screen never offers — the only buttons on a marked row are the answers it does
		// not currently hold — so this is the double press and the stale tab, and the honest response
		// to both is that the row already says what was asked for.
		if (was != null && was == attended) {
			return;
		}

		if (was == null) {
			// A first answer, arriving late. `attendance_recorded_at` is what V107's
			// shift_signups_attendance_whole CHECK requires beside a mark, and the correction columns
			// stay null because nothing was corrected.
			jdbc.update("""
					UPDATE shift_signups SET attended = ?, attendance_recorded_at = now()
					WHERE id = ?
					""", attended, signupId);
		} else {
			// A change of answer. `attendance_recorded_at` is deliberately left alone: it is when this
			// shift was marked, which is a fact about the marking and not about this row's current
			// value, and the roster's "Attendance recorded …" line reads it.
			jdbc.update("""
					UPDATE shift_signups
					SET attended = ?, attendance_corrected_at = now(), attendance_corrected_by = ?
					WHERE id = ?
					""", attended, actor.getUserId(), signupId);
		}

		// Its own action rather than a second marking event, for the reason MEAL_CORRECTED and
		// COMMUNICATION_RETRIED are their own: a log that recorded a correction as a marking would
		// make the two indistinguishable to anybody counting or filtering, and a correction is
		// precisely what somebody reading this log has come looking for.
		//
		// The before-state carries the previous answer in the words the roster uses, including
		// "not marked" for the third state — an audit entry that rendered null as `false` would
		// report an accusation that was never made. The entity is the shift, because that is what a
		// reader has in hand; the volunteer is named in the states themselves, so the entry is
		// legible without resolving anybody's id.
		Map<String, Object> before = new java.util.LinkedHashMap<>();
		before.put("volunteer", fullName);
		before.put("attended", was == null ? "not marked" : String.valueOf(was));
		if (row.get("attendance_corrected_at") != null) {
			// This row has been corrected before. Read back from the row rather than assumed, so the
			// trail of a sequence of corrections says when the previous one happened.
			before.put("lastCorrectedAt", String.valueOf(row.get("attendance_corrected_at")));
		}
		auditService.record(actor, AuditAction.ATTENDANCE_CORRECTED, AuditEntityType.SHIFT, shiftId,
				before, Map.of("volunteer", fullName, "attended", String.valueOf(attended)), null);
	}

	/** The one release, reached by the volunteer's own endpoint and the coordinator's alike. */
	private List<UUID> releaseSignup(UUID shiftId, UUID volunteerUserId) {
		LockedShift shift = lockShift(shiftId);
		LocalDateTime start = ShiftWindow.startsAt(shift.shiftDate(), shift.startTime());
		if (!start.isAfter(LocalDateTime.now(clock.zone()))) {
			throw new ApplicationException(ErrorCode.SHIFT_ALREADY_STARTED, Map.of("shiftId", shiftId));
		}
		List<UUID> releasedIds = jdbc.query("""
				UPDATE shift_signups SET released_at = now()
				WHERE shift_id = ? AND volunteer_user_id = ? AND released_at IS NULL
				RETURNING id
				""", (rs, n) -> rs.getObject("id", UUID.class), shiftId, volunteerUserId);
		if (releasedIds.isEmpty()) {
			throw new ApplicationException(ErrorCode.NOT_ON_SHIFT, Map.of("shiftId", shiftId));
		}
		reminderScheduler.cancelForSignup(shiftId, releasedIds.get(0));
		return promoteWithinLock(shiftId, shift);
	}

	/**
	 * Joins the waitlist of a full shift (E6-S5). Only for a shift that is genuinely full — if a spot
	 * is open, the volunteer signs up directly instead.
	 */
	@Transactional
	public void joinWaitlist(UUID volunteerUserId, UUID shiftId) {
		LockedShift shift = lockShift(shiftId);
		guardOpenAndFuture(shift);

		Integer signedUp = jdbc.queryForObject("""
				SELECT count(*) FROM shift_signups
				WHERE shift_id = ? AND volunteer_user_id = ? AND released_at IS NULL
				""", Integer.class, shiftId, volunteerUserId);
		if (signedUp != null && signedUp > 0) {
			throw new ApplicationException(ErrorCode.ALREADY_SIGNED_UP, Map.of("shiftId", shiftId));
		}
		if (signedUpCount(shiftId) < shift.capacity()) {
			throw new ApplicationException(ErrorCode.SHIFT_NOT_FULL, Map.of("shiftId", shiftId));
		}
		Integer waiting = jdbc.queryForObject("""
				SELECT count(*) FROM shift_waitlist
				WHERE shift_id = ? AND volunteer_user_id = ? AND promoted_at IS NULL AND left_at IS NULL
				""", Integer.class, shiftId, volunteerUserId);
		if (waiting != null && waiting > 0) {
			throw new ApplicationException(ErrorCode.ALREADY_ON_WAITLIST, Map.of("shiftId", shiftId));
		}
		jdbc.update("""
				INSERT INTO shift_waitlist (id, tenant_id, shift_id, volunteer_user_id)
				VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?)
				""", shiftId, volunteerUserId);
	}

	/** Leaves the waitlist (E6-S5), removing promotion eligibility immediately. */
	@Transactional
	public void leaveWaitlist(UUID volunteerUserId, UUID shiftId) {
		lockShift(shiftId); // serialise against a concurrent promotion
		int left = jdbc.update("""
				UPDATE shift_waitlist SET left_at = now()
				WHERE shift_id = ? AND volunteer_user_id = ? AND promoted_at IS NULL AND left_at IS NULL
				""", shiftId, volunteerUserId);
		if (left == 0) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("shiftId", shiftId));
		}
	}

	/**
	 * Promotes waitlisted volunteers into any open spots (E6-S5) — used when capacity is raised.
	 * Returns the user ids promoted, for the caller to notify.
	 */
	@Transactional
	public List<UUID> promoteWaitlist(UUID shiftId) {
		LockedShift shift = lockShift(shiftId);
		if (!"OPEN".equals(shift.status())) {
			return List.of();
		}
		return promoteWithinLock(shiftId, shift);
	}

	@Transactional(readOnly = true)
	public List<MyWaitlistView> myWaitlist(UUID volunteerUserId) {
		return jdbc.query("""
				SELECT s.id AS shift_id, s.title, s.shift_date, s.start_time, s.end_time, s.location,
					   w.joined_at,
					   (SELECT count(*) FROM shift_waitlist w2 WHERE w2.shift_id = s.id
							AND w2.promoted_at IS NULL AND w2.left_at IS NULL AND w2.joined_at <= w.joined_at) AS position
				FROM shift_waitlist w JOIN shifts s ON s.id = w.shift_id
				WHERE w.volunteer_user_id = ? AND w.promoted_at IS NULL AND w.left_at IS NULL
				  AND s.status = 'OPEN' AND s.shift_date >= CURRENT_DATE
				ORDER BY s.shift_date, s.start_time
				""", (rs, n) -> new MyWaitlistView(
				rs.getObject("shift_id", UUID.class), rs.getString("title"),
				rs.getObject("shift_date", LocalDate.class), rs.getObject("start_time", LocalTime.class),
				rs.getObject("end_time", LocalTime.class), rs.getString("location"),
				rs.getInt("position"), toInstant(rs.getObject("joined_at", OffsetDateTime.class))),
				volunteerUserId);
	}

	/** Best-effort "you're in" notice to a promoted volunteer (E6-S5). Unchanged by T-080. */
	public void notifyPromotion(UUID volunteerUserId, UUID shiftId) {
		notifyShift(volunteerUserId, shiftId, NotificationTemplate.WAITLIST_PROMOTED);
	}

	/**
	 * Best-effort "you're no longer on this shift" notice to the volunteer a coordinator removed
	 * (T-080) — the message whose absence was the whole defect. The promoted volunteer was told a
	 * spot had opened; the person whose spot it had been was told nothing.
	 *
	 * <p><strong>The reason, and never the note.</strong> Only {@code reason.volunteerText()} is put
	 * in the parameter map, so the internal note has no route to any channel: it is not a parameter
	 * of {@link NotificationTemplate#REMOVED_FROM_SHIFT}, it is not in the map this builds, and the
	 * notification row therefore cannot carry it. Said out loud because the proof of it is an
	 * absence, and an absence is the one thing a green test run does not show you.
	 */
	public void notifyRemoval(UUID volunteerUserId, UUID shiftId, RemoveVolunteerRequest.Reason reason) {
		notifyShift(volunteerUserId, shiftId, NotificationTemplate.REMOVED_FROM_SHIFT,
				Map.of("reason", reason.volunteerText()));
	}

	/** Promotes as many waitlist heads as there are free spots. Assumes the shift row is locked. */
	private List<UUID> promoteWithinLock(UUID shiftId, LockedShift shift) {
		List<UUID> promoted = new java.util.ArrayList<>();
		int free = shift.capacity() - signedUpCount(shiftId);
		while (free > 0) {
			List<Map<String, Object>> head = jdbc.queryForList("""
					SELECT id, volunteer_user_id FROM shift_waitlist
					WHERE shift_id = ? AND promoted_at IS NULL AND left_at IS NULL
					ORDER BY joined_at LIMIT 1
					""", shiftId);
			if (head.isEmpty()) {
				break;
			}
			UUID waitlistId = (UUID) head.get(0).get("id");
			UUID userId = (UUID) head.get(0).get("volunteer_user_id");
			jdbc.update("UPDATE shift_waitlist SET promoted_at = now() WHERE id = ?", waitlistId);
			UUID newSignupId = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO shift_signups (id, tenant_id, shift_id, volunteer_user_id, source)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, 'PROMOTION')
					""", newSignupId, shiftId, userId);
			// A promoted volunteer enters the normal reminder flow for the remaining offsets (E6-S6).
			reminderScheduler.scheduleForSignup(newSignupId);
			promoted.add(userId);
			free--;
		}
		return promoted;
	}

	@Transactional(readOnly = true)
	public List<AvailableShiftView> availableShifts(UUID volunteerUserId, LocalDate from, LocalDate to) {
		LocalDate fromDate = from != null ? from : LocalDate.now(clock.zone());
		StringBuilder sql = new StringBuilder("""
				SELECT s.id, s.title, s.description, s.shift_date, s.start_time, s.end_time, s.location,
					   s.capacity,
					   (SELECT count(*) FROM shift_signups ss WHERE ss.shift_id = s.id AND ss.released_at IS NULL) AS signed_up,
					   (SELECT count(*) FROM shift_waitlist w WHERE w.shift_id = s.id AND w.promoted_at IS NULL AND w.left_at IS NULL) AS waitlisted,
					   EXISTS (SELECT 1 FROM shift_signups ss WHERE ss.shift_id = s.id AND ss.volunteer_user_id = ? AND ss.released_at IS NULL) AS caller_signed_up,
					   EXISTS (SELECT 1 FROM shift_waitlist w WHERE w.shift_id = s.id AND w.volunteer_user_id = ? AND w.promoted_at IS NULL AND w.left_at IS NULL) AS caller_waitlisted
				FROM shifts s
				WHERE s.status = 'OPEN' AND s.shift_date >= ?
				""");
		if (to != null) {
			sql.append(" AND s.shift_date <= ?");
		}
		sql.append(" ORDER BY s.shift_date, s.start_time");
		Object[] args = to != null
				? new Object[] {volunteerUserId, volunteerUserId, fromDate, to}
				: new Object[] {volunteerUserId, volunteerUserId, fromDate};

		return jdbc.query(sql.toString(), (rs, n) -> {
			int capacity = rs.getInt("capacity");
			int signedUp = rs.getInt("signed_up");
			String state = rs.getBoolean("caller_signed_up") ? "SIGNED_UP"
					: rs.getBoolean("caller_waitlisted") ? "WAITLISTED"
					: signedUp >= capacity ? "FULL" : "AVAILABLE";
			return new AvailableShiftView(
					rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("description"),
					rs.getObject("shift_date", LocalDate.class), rs.getObject("start_time", LocalTime.class),
					rs.getObject("end_time", LocalTime.class), rs.getString("location"), capacity,
					signedUp, rs.getInt("waitlisted"), state);
		}, args);
	}

	@Transactional(readOnly = true)
	public List<MyShiftView> myShifts(UUID volunteerUserId) {
		return jdbc.query("""
				SELECT ss.id AS signup_id, ss.source, ss.signed_up_at, s.id AS shift_id, s.title,
					   s.shift_date, s.start_time, s.end_time, s.location
				FROM shift_signups ss JOIN shifts s ON s.id = ss.shift_id
				WHERE ss.volunteer_user_id = ? AND ss.released_at IS NULL
				  AND s.status = 'OPEN' AND s.shift_date >= CURRENT_DATE
				ORDER BY s.shift_date, s.start_time
				""", (rs, n) -> new MyShiftView(
				rs.getObject("signup_id", UUID.class), rs.getObject("shift_id", UUID.class),
				rs.getString("title"), rs.getObject("shift_date", LocalDate.class),
				rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class),
				rs.getString("location"), rs.getString("source"),
				toInstant(rs.getObject("signed_up_at", OffsetDateTime.class))), volunteerUserId);
	}

	/** Best-effort signup confirmation to the volunteer (E6-S3). */
	public void notifyConfirmation(UUID volunteerUserId, UUID shiftId) {
		notifyShift(volunteerUserId, shiftId, NotificationTemplate.SHIFT_SIGNUP_CONFIRMED);
	}

	// ---- shared helpers (used by S4/S5 too) -----------------------------

	LockedShift lockShift(UUID shiftId) {
		try {
			return jdbc.queryForObject("""
					SELECT status, capacity, shift_date, start_time, end_time, title
					FROM shifts WHERE id = ? FOR UPDATE
					""", (rs, n) -> new LockedShift(
					rs.getString("status"), rs.getInt("capacity"),
					rs.getObject("shift_date", LocalDate.class), rs.getObject("start_time", LocalTime.class),
					rs.getObject("end_time", LocalTime.class), rs.getString("title")), shiftId);
		} catch (EmptyResultDataAccessException e) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("shiftId", shiftId), e);
		}
	}

	int signedUpCount(UUID shiftId) {
		Integer n = jdbc.queryForObject(
				"SELECT count(*) FROM shift_signups WHERE shift_id = ? AND released_at IS NULL",
				Integer.class, shiftId);
		return n == null ? 0 : n;
	}

	void guardOpenAndFuture(LockedShift shift) {
		if (!"OPEN".equals(shift.status())) {
			throw new ApplicationException(ErrorCode.SHIFT_NOT_OPEN, Map.of());
		}
		LocalDateTime start = ShiftWindow.startsAt(shift.shiftDate(), shift.startTime());
		if (!start.isAfter(LocalDateTime.now(clock.zone()))) {
			throw new ApplicationException(ErrorCode.SHIFT_ALREADY_STARTED, Map.of());
		}
	}

	void notifyShift(UUID volunteerUserId, UUID shiftId, NotificationTemplate template) {
		notifyShift(volunteerUserId, shiftId, template, Map.of());
	}

	/**
	 * The five facts every shift message here carries, plus whatever one template needs beyond them.
	 *
	 * <p>{@code extra} exists for T-080's removal reason and is kept deliberately narrow: it is
	 * merged <em>after</em> the shift's own facts, so a caller could in principle overwrite one of
	 * them, and nothing does. Anything a template must not send simply is not passed here — the
	 * parameter map is what lands in {@code notifications.params}, so it is the boundary, not a
	 * formatting step before one.
	 *
	 * <p>The {@code time} parameter goes through {@link ShiftWindow#describe}, which says "(next
	 * day)" where the shift runs through midnight (T-146). It used to be the two raw column values
	 * with an en dash between them, so the Janmashtami midnight offering would have reached a
	 * volunteer's phone as "20:00:00–02:00:00" — a shift that ends sixteen hours before it begins,
	 * for somebody deciding whether they can make it. The screens print the same sentence, so the
	 * message and the roster it came from agree word for word.
	 */
	void notifyShift(UUID volunteerUserId, UUID shiftId, NotificationTemplate template,
			Map<String, Object> extra) {
		try {
			// Read through a RowMapper rather than queryForMap, because the two times are needed as
			// LocalTime and not as whatever the driver hands back for a TIME column: the old string
			// concatenation worked on java.sql.Time's toString() and so could not have been asked
			// the one question that matters here — does this shift cross midnight?
			Map<String, Object> s = jdbc.queryForObject("""
					SELECT title, shift_date, start_time, end_time, location FROM shifts WHERE id = ?
					""", (rs, n) -> {
				Map<String, Object> row = new java.util.LinkedHashMap<>();
				row.put("title", rs.getString("title"));
				row.put("date", rs.getObject("shift_date", LocalDate.class));
				row.put("time", ShiftWindow.describe(
						rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class)));
				row.put("location", rs.getString("location"));
				return row;
			}, shiftId);
			String temple = templeName();
			String location = s.get("location") != null ? s.get("location").toString() : temple;
			Map<String, Object> params = new java.util.LinkedHashMap<>(Map.of(
					"title", str(s.get("title")), "date", str(s.get("date")),
					"time", str(s.get("time")), "location", location, "temple", temple));
			params.putAll(extra);
			notificationService.notify(NotificationRecipient.user(volunteerUserId), template, params, null);
		} catch (RuntimeException e) {
			log.warn("Could not queue {} to {} for shift {}: {}", template, volunteerUserId, shiftId, e.toString());
		}
	}

	/**
	 * Is this volunteer already on a shift running at the same time as the one they are claiming?
	 *
	 * <p>It warns and never blocks — real families share duties — but it has to be right in both
	 * directions, because a wrong answer is either a volunteer double-booked with nobody told or a
	 * volunteer warned off a shift that clashes with nothing.
	 *
	 * <p><strong>Rewritten for T-146, and this is where an overnight shift did real damage.</strong>
	 * The old query was same-day interval arithmetic:
	 *
	 * <pre>AND s2.shift_date = ? AND s2.start_time &lt; ? AND s2.end_time &gt; ?</pre>
	 *
	 * <p>That compares clock times within one calendar date, which is only a comparison of moments
	 * while every shift begins and ends on its own date. It is wrong when <em>either</em> shift
	 * crosses midnight, and in opposite ways. As the subject: claiming 20:00–02:00 against a
	 * 23:00–01:00 spot already held reads "23:00 &lt; 02:00" as false and reports no clash, so the
	 * volunteer is double-booked for the whole of the festival's busiest night. As the neighbour: a
	 * 22:00–06:00 shift already held would be compared on {@code end_time} of 06:00, which sits
	 * before its own start and matches morning shifts on the following day that it has nothing to do
	 * with — a clash invented out of arithmetic.
	 *
	 * <p>So both sides are compared as <em>moments</em>. The subject's two instants are built in Java
	 * through {@link ShiftWindow}; the neighbour's end is built in SQL through {@code
	 * shift_ends_at()} (V127), because it belongs to a row the query is looking at rather than to a
	 * shift this method is holding. Its start needs no function — {@code shift_date + start_time} is
	 * the start instant by definition, since {@code shift_date} is the date a shift begins.
	 *
	 * <p>The standard half-open overlap test: two windows overlap when each begins before the other
	 * ends. Touching ends do not overlap, which is deliberate and unchanged — a volunteer finishing
	 * at 12:00 and starting again at 12:00 is doing two shifts back to back, not two at once, and
	 * warning them about it would teach them to ignore the warning.
	 *
	 * <p>The {@code shift_date = ?} equality that used to narrow this is gone with the arithmetic it
	 * belonged to, so the check now reads every active signup this volunteer holds rather than only
	 * that day's. That is a handful of rows per person — a volunteer's roster, not the temple's —
	 * and the join is by primary key. A date window could be put back as an optimisation if a
	 * temple ever has a devotee with thousands of open signups; narrowing it by a day would
	 * reintroduce exactly the defect above, since the shift that clashes with a midnight shift is
	 * usually dated the day before.
	 *
	 * <p>A cancelled shift still counts as a clash here, unchanged from before T-146: cancelling
	 * does not release the signups, so a volunteer is still "on" it as far as this query is
	 * concerned. Left alone deliberately — it is a separate question from the one this task was
	 * given, it errs towards warning rather than towards silence, and it is noted rather than
	 * quietly changed under cover of a different fix.
	 */
	private boolean overlaps(UUID volunteerUserId, UUID shiftId, LockedShift shift) {
		LocalDateTime startsAt = ShiftWindow.startsAt(shift.shiftDate(), shift.startTime());
		LocalDateTime endsAt = ShiftWindow.endsAt(shift.shiftDate(), shift.startTime(), shift.endTime());
		Integer n = jdbc.queryForObject("""
				SELECT count(*) FROM shift_signups ss JOIN shifts s2 ON s2.id = ss.shift_id
				WHERE ss.volunteer_user_id = ? AND ss.released_at IS NULL AND s2.id <> ?
				  AND (s2.shift_date + s2.start_time) < ?
				  AND shift_ends_at(s2.shift_date, s2.start_time, s2.end_time) > ?
				""", Integer.class, volunteerUserId, shiftId, endsAt, startsAt);
		return n != null && n > 0;
	}

	private String templeName() {
		try {
			return jdbc.queryForObject("""
					SELECT name FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""", String.class);
		} catch (RuntimeException e) {
			return "the temple";
		}
	}

	private static String str(Object o) {
		return o == null ? "" : o.toString();
	}

	private static java.time.Instant toInstant(OffsetDateTime odt) {
		return odt == null ? null : odt.toInstant();
	}

	/** The locked shift's fields needed to decide a signup/release/promotion. */
	record LockedShift(String status, int capacity, LocalDate shiftDate, LocalTime startTime,
			LocalTime endTime, String title) {
	}
}
