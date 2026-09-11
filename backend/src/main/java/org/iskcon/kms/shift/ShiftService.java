package org.iskcon.kms.shift;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.notification.NotificationRecipient;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.notification.NotificationTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posting and managing volunteer shifts (E6-S2). Creation is publication — a new shift is visible to
 * volunteers at once. Reminder offsets are stored per shift; the jobs that act on them are scheduled
 * in E6-S6. Cancelling a shift closes it to signups and notifies everyone signed up or waitlisted.
 *
 * <p>Since D-14 a shift may also say which meal it was posted for, and that changes what it counts
 * toward: a linked shift counts toward its own meal and no other, an unlinked one keeps counting
 * toward every meal its hours span. The link is the meal's natural key — a date, a kind and, for an
 * event, its name — because there is no meal row to point at when a shift is posted. See
 * {@link #listCountingTowardMeals} for what the crew count reads, and {@code requireWholeMealLink}
 * for why half a link is refused.
 */
@Service
public class ShiftService {

	private static final Logger log = LoggerFactory.getLogger(ShiftService.class);
	private static final List<Integer> DEFAULT_OFFSETS = List.of(1440); // one 24h reminder

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;
	private final NotificationService notificationService;

	public ShiftService(JdbcTemplate jdbc, ObjectMapper objectMapper,
			NotificationService notificationService) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
		this.notificationService = notificationService;
	}

	@Transactional(readOnly = true)
	public List<ShiftView> list(LocalDate from, LocalDate to, boolean includeCancelled) {
		StringBuilder sql = new StringBuilder(SELECT + " WHERE 1 = 1");
		List<Object> args = new ArrayList<>();
		if (!includeCancelled) {
			sql.append(" AND s.status = 'OPEN'");
		}
		if (from != null) {
			sql.append(" AND s.shift_date >= ?");
			args.add(from);
		}
		if (to != null) {
			sql.append(" AND s.shift_date <= ?");
			args.add(to);
		}
		sql.append(" ORDER BY s.shift_date, s.start_time");
		return jdbc.query(sql.toString(), mapper(), args.toArray());
	}

	@Transactional(readOnly = true)
	public ShiftView get(UUID id) {
		return findShift(id).orElseThrow(() -> notFound(id));
	}

	/** The poster's roster (E6-S4+): signups (including released spots) and the waitlist. */
	@Transactional(readOnly = true)
	public RosterView roster(UUID id) {
		ShiftView shift = findShift(id).orElseThrow(() -> notFound(id));

		// Reminder delivery status per signup, joined through to the notification the reminder produced.
		Map<UUID, List<RosterView.Reminder>> reminders = new java.util.LinkedHashMap<>();
		jdbc.query("""
				SELECT sr.signup_id, sr.offset_minutes, n.final_channel, n.preferred_channel, n.status
				FROM shift_reminders sr LEFT JOIN notifications n ON n.id = sr.notification_id
				WHERE sr.shift_id = ? ORDER BY sr.offset_minutes DESC
				""", rs -> {
			UUID signupId = rs.getObject("signup_id", UUID.class);
			String channel = rs.getString("final_channel") != null
					? rs.getString("final_channel") : rs.getString("preferred_channel");
			reminders.computeIfAbsent(signupId, k -> new java.util.ArrayList<>()).add(
					new RosterView.Reminder(rs.getInt("offset_minutes"), channel, rs.getString("status")));
		}, id);

		// `attended` is read through getObject(Boolean.class) rather than getBoolean(), which cannot
		// express the unmarked case at all: it answers false for SQL NULL. That is precisely the
		// distinction B7 exists to keep, so the one place it would be silently thrown away is here.
		//
		// `c` joins the corrector's name the same way `sent_by_name` is joined for a broadcast below
		// (T-099): LEFT, because `attendance_corrected_by` is nullable on every row that has never
		// been corrected and ON DELETE SET NULL on one that has, so a departed coordinator's past
		// correction must not turn the join into an INNER one and drop the row's own mark with it.
		List<RosterView.Signup> signups = jdbc.query("""
				SELECT ss.id, ss.volunteer_user_id, u.full_name, ss.source, ss.signed_up_at, ss.released_at,
					   ss.released_reason, ss.released_note,
					   ss.attended, ss.attendance_recorded_at, ss.attendance_corrected_at,
					   c.full_name AS corrected_by_name
				FROM shift_signups ss
				JOIN users u ON u.id = ss.volunteer_user_id
				LEFT JOIN users c ON c.id = ss.attendance_corrected_by
				WHERE ss.shift_id = ? ORDER BY ss.signed_up_at
				""", (rs, n) -> new RosterView.Signup(
				rs.getObject("volunteer_user_id", UUID.class), rs.getString("full_name"),
				rs.getString("source"), toInstant(rs.getObject("signed_up_at", OffsetDateTime.class)),
				toInstant(rs.getObject("released_at", OffsetDateTime.class)),
				// T-080. Null together on a volunteer's own release and present together on a
				// coordinator's removal — V124 has a CHECK saying they cannot arrive apart — so the
				// screen reads one of them to know which of the two acts it is looking at. The note is
				// internal: it goes to this roster and to the audit trail, and to nothing that sends.
				rs.getString("released_reason"), rs.getString("released_note"),
				rs.getObject("attended", Boolean.class),
				toInstant(rs.getObject("attendance_recorded_at", OffsetDateTime.class)),
				toInstant(rs.getObject("attendance_corrected_at", OffsetDateTime.class)),
				rs.getString("corrected_by_name"),
				reminders.getOrDefault(rs.getObject("id", UUID.class), List.of())), id);
		List<RosterView.Waitlister> waitlist = jdbc.query("""
				SELECT w.volunteer_user_id, u.full_name, w.joined_at,
					   row_number() OVER (ORDER BY w.joined_at) AS position
				FROM shift_waitlist w JOIN users u ON u.id = w.volunteer_user_id
				WHERE w.shift_id = ? AND w.promoted_at IS NULL AND w.left_at IS NULL
				ORDER BY w.joined_at
				""", (rs, n) -> new RosterView.Waitlister(
				rs.getObject("volunteer_user_id", UUID.class), rs.getString("full_name"),
				rs.getInt("position"), toInstant(rs.getObject("joined_at", OffsetDateTime.class))), id);

		// Broadcasts (E6-S7), each with its per-recipient delivery status.
		Map<UUID, List<RosterView.Recipient>> byBroadcast = new java.util.LinkedHashMap<>();
		jdbc.query("""
				SELECT br.broadcast_id, u.full_name, n.final_channel, n.preferred_channel, n.status
				FROM shift_broadcast_recipients br
				JOIN users u ON u.id = br.recipient_user_id
				LEFT JOIN notifications n ON n.id = br.notification_id
				WHERE br.broadcast_id IN (SELECT id FROM shift_broadcasts WHERE shift_id = ?)
				ORDER BY u.full_name
				""", rs -> {
			String channel = rs.getString("final_channel") != null
					? rs.getString("final_channel") : rs.getString("preferred_channel");
			byBroadcast.computeIfAbsent(rs.getObject("broadcast_id", UUID.class), k -> new java.util.ArrayList<>())
					.add(new RosterView.Recipient(rs.getString("full_name"), channel, rs.getString("status")));
		}, id);
		List<RosterView.Broadcast> broadcasts = jdbc.query("""
				SELECT b.id, b.message, u.full_name AS sent_by_name, b.created_at
				FROM shift_broadcasts b LEFT JOIN users u ON u.id = b.sent_by
				WHERE b.shift_id = ? ORDER BY b.created_at DESC
				""", (rs, n) -> new RosterView.Broadcast(
				rs.getString("message"), rs.getString("sent_by_name"),
				toInstant(rs.getObject("created_at", OffsetDateTime.class)),
				byBroadcast.getOrDefault(rs.getObject("id", UUID.class), List.of())), id);

		return new RosterView(shift, signups, waitlist, broadcasts);
	}

	@Transactional
	public UUID create(AuthenticatedUser actor, CreateShiftRequest request) {
		requireWholeMealLink(request.mealDate(), request.mealKind(), request.mealEventName());
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO shifts (
					id, tenant_id, title, description, shift_date, start_time, end_time, location,
					capacity, reminder_offsets_minutes, created_by, meal_date, meal_kind, meal_event_name)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
					?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
				""", id, request.title().trim(), trimToNull(request.description()), request.shiftDate(),
				request.startTime(), request.endTime(), trimToNull(request.location()), request.capacity(),
				offsetsJson(request.reminderOffsetsMinutes()), actor.getUserId(),
				request.mealDate(), trimToNull(request.mealKind()), trimToNull(request.mealEventName()));
		return id;
	}

	@Transactional
	public void update(UUID id, UpdateShiftRequest request) {
		requireOpen(id);
		requireWholeMealLink(request.mealDate(), request.mealKind(), request.mealEventName());
		// An edit rewrites every field, the link included: a shift edited back to no meal at all
		// becomes unlinked and starts counting by its hours again. That is the only way to undo a
		// link that was made in error, and leaving the old one in place because the new request was
		// silent about it would make the mistake permanent.
		jdbc.update("""
				UPDATE shifts SET title = ?, description = ?, shift_date = ?, start_time = ?, end_time = ?,
					location = ?, capacity = ?, reminder_offsets_minutes = CAST(? AS jsonb),
					meal_date = ?, meal_kind = ?, meal_event_name = ?, updated_at = now()
				WHERE id = ?
				""", request.title().trim(), trimToNull(request.description()), request.shiftDate(),
				request.startTime(), request.endTime(), trimToNull(request.location()), request.capacity(),
				offsetsJson(request.reminderOffsetsMinutes()),
				request.mealDate(), trimToNull(request.mealKind()), trimToNull(request.mealEventName()), id);
	}

	/** Cancels a shift; returns nothing. The apology notifications are sent by {@link #notifyCancellation}. */
	@Transactional
	public void cancel(UUID id, String reason) {
		requireOpen(id);
		jdbc.update("""
				UPDATE shifts SET status = 'CANCELLED', cancel_reason = ?, cancelled_at = now(), updated_at = now()
				WHERE id = ?
				""", reason.trim(), id);
	}

	/**
	 * Best-effort apology to everyone signed up or waitlisted on a now-cancelled shift (E6-S2). Sent
	 * outside the cancel transaction so a notification that can't be queued never undoes the cancel.
	 */
	public void notifyCancellation(UUID id) {
		ShiftView shift = findShift(id).orElse(null);
		if (shift == null) {
			return;
		}
		String temple = templeName();
		List<UUID> recipients = new ArrayList<>();
		recipients.addAll(jdbc.queryForList(
				"SELECT volunteer_user_id FROM shift_signups WHERE shift_id = ? AND released_at IS NULL",
				UUID.class, id));
		recipients.addAll(jdbc.queryForList("""
				SELECT volunteer_user_id FROM shift_waitlist
				WHERE shift_id = ? AND promoted_at IS NULL AND left_at IS NULL
				""", UUID.class, id));
		for (UUID userId : recipients) {
			try {
				notificationService.notify(
						NotificationRecipient.user(userId),
						NotificationTemplate.SHIFT_CANCELLED,
						Map.of("title", shift.title(), "date", shift.shiftDate().toString(), "temple", temple),
						null);
			} catch (RuntimeException e) {
				log.warn("Could not queue cancellation notice to {} for shift {}: {}", userId, id, e.toString());
			}
		}
	}

	/**
	 * Every open shift that could count toward a meal in this range: the ones falling inside it, and
	 * the ones <em>linked</em> to a meal inside it however far ahead they were posted (D-14).
	 *
	 * <p>The second half is not decoration. A linked shift counts toward its meal whatever the clock
	 * says, and the clock includes the calendar: "grind the masala on Thursday for Sunday's feast" is
	 * a shift on Thursday committed to Sunday lunch, and a range built from the meals' dates would
	 * never load it. Loading it by shift date alone is how the link would read zero on precisely the
	 * shift somebody took the trouble to link.
	 *
	 * <p>Widening by a fixed number of days either side was the alternative and was rejected: any
	 * number chosen is a guess about how far ahead a temple prepares, and the one time it is too
	 * small the count is silently short.
	 */
	@Transactional(readOnly = true)
	public List<ShiftView> listCountingTowardMeals(LocalDate from, LocalDate to) {
		return jdbc.query(SELECT + """
				WHERE s.status = 'OPEN'
				  AND ((s.shift_date BETWEEN ? AND ?) OR (s.meal_date BETWEEN ? AND ?))
				ORDER BY s.shift_date, s.start_time
				""", mapper(), from, to, from, to);
	}

	// ---------------------------------------------------------------------

	/**
	 * A shift is linked to a meal or it is not; there is no half of it (D-14).
	 *
	 * <p>The date and the kind are the meal's identity and neither identifies it alone, so a request
	 * carrying one is refused by name rather than saved. Saved, it would count toward no meal at all
	 * while reading on the planner as a shift that had been deliberately committed to one — the
	 * quietest possible way to lose a pair of hands. An event name on its own is refused for the same
	 * reason: it names nothing without a date and a kind beside it.
	 *
	 * <p>The database carries the same rule as a CHECK. This is the one the caller reads.
	 */
	private static void requireWholeMealLink(
			LocalDate mealDate, String mealKind, String mealEventName) {

		boolean hasDate = mealDate != null;
		boolean hasKind = trimToNull(mealKind) != null;
		boolean hasEvent = trimToNull(mealEventName) != null;
		if (hasDate != hasKind || (hasEvent && !hasDate)) {
			throw new ApplicationException(ErrorCode.SHIFT_MEAL_LINK_INCOMPLETE, Map.of(
					"mealDate", String.valueOf(mealDate),
					"mealKind", String.valueOf(mealKind),
					"mealEventName", String.valueOf(mealEventName)));
		}
	}

	private void requireOpen(UUID id) {
		String status = jdbc.query("SELECT status FROM shifts WHERE id = ?",
				(rs, n) -> rs.getString("status"), id).stream().findFirst()
				.orElseThrow(() -> notFound(id));
		if (!"OPEN".equals(status)) {
			throw new ApplicationException(ErrorCode.SHIFT_NOT_OPEN, Map.of("shiftId", id));
		}
	}

	private Optional<ShiftView> findShift(UUID id) {
		return jdbc.query(SELECT + " WHERE s.id = ?", mapper(), id).stream().findFirst();
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

	private String offsetsJson(List<Integer> offsets) {
		List<Integer> effective = (offsets == null || offsets.isEmpty()) ? DEFAULT_OFFSETS
				: offsets.stream().distinct().sorted().toList();
		try {
			return objectMapper.writeValueAsString(effective);
		} catch (JsonProcessingException e) {
			throw new ApplicationException(ErrorCode.UNEXPECTED_FAILURE, Map.of(), e);
		}
	}

	private List<Integer> parseOffsets(String json) {
		if (json == null || json.isBlank()) {
			return DEFAULT_OFFSETS;
		}
		try {
			return objectMapper.readValue(json, new TypeReference<List<Integer>>() {
			});
		} catch (JsonProcessingException e) {
			return DEFAULT_OFFSETS;
		}
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	private ApplicationException notFound(UUID id) {
		return new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("shiftId", id));
	}

	private RowMapper<ShiftView> mapper() {
		return (rs, n) -> new ShiftView(
				rs.getObject("id", UUID.class),
				rs.getString("title"),
				rs.getString("description"),
				rs.getObject("shift_date", LocalDate.class),
				rs.getObject("start_time", java.time.LocalTime.class),
				rs.getObject("end_time", java.time.LocalTime.class),
				rs.getString("location"),
				rs.getInt("capacity"),
				parseOffsets(rs.getString("reminder_offsets_minutes")),
				rs.getString("status"),
				rs.getString("cancel_reason"),
				rs.getInt("signed_up"),
				rs.getInt("waitlisted"),
				toInstant(rs.getObject("created_at", OffsetDateTime.class)),
				rs.getObject("meal_date", LocalDate.class),
				rs.getString("meal_kind"),
				rs.getString("meal_event_name"));
	}

	private static final String SELECT = """
			SELECT s.id, s.title, s.description, s.shift_date, s.start_time, s.end_time, s.location,
				   s.capacity, s.reminder_offsets_minutes, s.status, s.cancel_reason, s.created_at,
				   s.meal_date, s.meal_kind, s.meal_event_name,
				   (SELECT count(*) FROM shift_signups ss
						WHERE ss.shift_id = s.id AND ss.released_at IS NULL) AS signed_up,
				   (SELECT count(*) FROM shift_waitlist w
						WHERE w.shift_id = s.id AND w.promoted_at IS NULL AND w.left_at IS NULL) AS waitlisted
			FROM shifts s
			""";

	private static java.time.Instant toInstant(OffsetDateTime odt) {
		return odt == null ? null : odt.toInstant();
	}
}
