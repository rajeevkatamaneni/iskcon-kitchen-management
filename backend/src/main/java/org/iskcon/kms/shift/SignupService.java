package org.iskcon.kms.shift;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.notification.NotificationRecipient;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.notification.NotificationTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Volunteer signup for shifts (E6-S3), and — added by later stories — release (E6-S4) and the
 * waitlist (E6-S5).
 *
 * <p>The capacity claim is made safe under concurrency by locking the shift row
 * ({@code SELECT … FOR UPDATE}) at the start of the signup transaction: every signup, release, and
 * promotion for a shift serialises on that one row, so two simultaneous signups for the last spot
 * can never both succeed. An overlapping-time signup is allowed but flagged — real families share
 * duties, so it warns rather than blocks.
 */
@Service
public class SignupService {

	private static final Logger log = LoggerFactory.getLogger(SignupService.class);
	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	private final JdbcTemplate jdbc;
	private final NotificationService notificationService;
	private final ShiftReminderScheduler reminderScheduler;

	public SignupService(JdbcTemplate jdbc, NotificationService notificationService,
			ShiftReminderScheduler reminderScheduler) {
		this.jdbc = jdbc;
		this.notificationService = notificationService;
		this.reminderScheduler = reminderScheduler;
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
	 */
	@Transactional
	public List<UUID> release(UUID volunteerUserId, UUID shiftId) {
		LockedShift shift = lockShift(shiftId);
		LocalDateTime start = LocalDateTime.of(shift.shiftDate(), shift.startTime());
		if (!start.isAfter(LocalDateTime.now(TEMPLE_ZONE))) {
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
		LocalDate fromDate = from != null ? from : LocalDate.now(TEMPLE_ZONE);
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
		if (!start.isAfter(LocalDateTime.now(TEMPLE_ZONE))) {
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
