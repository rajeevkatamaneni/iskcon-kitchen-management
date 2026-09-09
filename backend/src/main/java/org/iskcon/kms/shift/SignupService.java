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
	 * {@code MANAGE_VOLUNTEER_SHIFTS}. Until now this could not be done at all: the only release in
	 * the system acted on the caller's own id, so a coordinator faced with a volunteer who had
	 * stopped answering had no way to free the spot — and no way to let the waitlist have it.
	 *
	 * <p>Identical in effect to a volunteer's own release, and that is the point: the spot is freed,
	 * the waitlist head is promoted into it, the release shows on the roster with its time, and the
	 * pending reminders for that signup are cancelled. What differs is who may ask, which is settled
	 * at the controller by the permission, and the argument that names the person — spelled out here
	 * rather than left to the argument order of {@link #release}, where {@code volunteerUserId} first
	 * and {@code shiftId} second is a trap worth not laying.
	 *
	 * <p>The started-shift guard is kept, deliberately. Releasing somebody off a shift that has
	 * already run would rewrite what the roster said at the time it mattered, and attendance (B7) is
	 * the right way to record that a person on the roster did not come.
	 */
	@Transactional
	public List<UUID> releaseVolunteer(UUID shiftId, UUID volunteerUserId) {
		return releaseSignup(shiftId, volunteerUserId);
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

		LocalDateTime start = LocalDateTime.of(shift.shiftDate(), shift.startTime());
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

		LocalDateTime start = LocalDateTime.of(shift.shiftDate(), shift.startTime());
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
		LocalDateTime start = LocalDateTime.of(shift.shiftDate(), shift.startTime());
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

	/** Best-effort "you're in" notice to a promoted volunteer (E6-S5). */
	public void notifyPromotion(UUID volunteerUserId, UUID shiftId) {
		notifyShift(volunteerUserId, shiftId, NotificationTemplate.WAITLIST_PROMOTED);
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
		LocalDateTime start = LocalDateTime.of(shift.shiftDate(), shift.startTime());
		if (!start.isAfter(LocalDateTime.now(clock.zone()))) {
			throw new ApplicationException(ErrorCode.SHIFT_ALREADY_STARTED, Map.of());
		}
	}

	void notifyShift(UUID volunteerUserId, UUID shiftId, NotificationTemplate template) {
		try {
			Map<String, Object> s = jdbc.queryForMap(
					"SELECT title, shift_date, start_time, end_time, location FROM shifts WHERE id = ?", shiftId);
			String temple = templeName();
			String location = s.get("location") != null ? s.get("location").toString() : temple;
			String time = s.get("start_time") + "–" + s.get("end_time");
			notificationService.notify(
					NotificationRecipient.user(volunteerUserId), template,
					Map.of("title", str(s.get("title")), "date", str(s.get("shift_date")),
							"time", time, "location", location, "temple", temple),
					null);
		} catch (RuntimeException e) {
			log.warn("Could not queue {} to {} for shift {}: {}", template, volunteerUserId, shiftId, e.toString());
		}
	}

	private boolean overlaps(UUID volunteerUserId, UUID shiftId, LockedShift shift) {
		Integer n = jdbc.queryForObject("""
				SELECT count(*) FROM shift_signups ss JOIN shifts s2 ON s2.id = ss.shift_id
				WHERE ss.volunteer_user_id = ? AND ss.released_at IS NULL AND s2.id <> ?
				  AND s2.shift_date = ? AND s2.start_time < ? AND s2.end_time > ?
				""", Integer.class, volunteerUserId, shiftId, shift.shiftDate(), shift.endTime(), shift.startTime());
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
