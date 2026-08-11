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
		List<RosterView.Signup> signups = jdbc.query("""
				SELECT ss.volunteer_user_id, u.full_name, ss.source, ss.signed_up_at, ss.released_at
				FROM shift_signups ss JOIN users u ON u.id = ss.volunteer_user_id
				WHERE ss.shift_id = ? ORDER BY ss.signed_up_at
				""", (rs, n) -> new RosterView.Signup(
				rs.getObject("volunteer_user_id", UUID.class), rs.getString("full_name"),
				rs.getString("source"), toInstant(rs.getObject("signed_up_at", OffsetDateTime.class)),
				toInstant(rs.getObject("released_at", OffsetDateTime.class))), id);
		List<RosterView.Waitlister> waitlist = jdbc.query("""
				SELECT w.volunteer_user_id, u.full_name, w.joined_at,
					   row_number() OVER (ORDER BY w.joined_at) AS position
				FROM shift_waitlist w JOIN users u ON u.id = w.volunteer_user_id
				WHERE w.shift_id = ? AND w.promoted_at IS NULL AND w.left_at IS NULL
				ORDER BY w.joined_at
				""", (rs, n) -> new RosterView.Waitlister(
				rs.getObject("volunteer_user_id", UUID.class), rs.getString("full_name"),
				rs.getInt("position"), toInstant(rs.getObject("joined_at", OffsetDateTime.class))), id);
		return new RosterView(shift, signups, waitlist);
	}

	@Transactional
	public UUID create(AuthenticatedUser actor, CreateShiftRequest request) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO shifts (
					id, tenant_id, title, description, shift_date, start_time, end_time, location,
					capacity, reminder_offsets_minutes, created_by)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
					?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
				""", id, request.title().trim(), trimToNull(request.description()), request.shiftDate(),
				request.startTime(), request.endTime(), trimToNull(request.location()), request.capacity(),
				offsetsJson(request.reminderOffsetsMinutes()), actor.getUserId());
		return id;
	}

	@Transactional
	public void update(UUID id, UpdateShiftRequest request) {
		requireOpen(id);
		jdbc.update("""
				UPDATE shifts SET title = ?, description = ?, shift_date = ?, start_time = ?, end_time = ?,
					location = ?, capacity = ?, reminder_offsets_minutes = CAST(? AS jsonb), updated_at = now()
				WHERE id = ?
				""", request.title().trim(), trimToNull(request.description()), request.shiftDate(),
				request.startTime(), request.endTime(), trimToNull(request.location()), request.capacity(),
				offsetsJson(request.reminderOffsetsMinutes()), id);
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

	@Transactional
	public UUID duplicate(AuthenticatedUser actor, UUID id, LocalDate newDate) {
		ShiftView s = findShift(id).orElseThrow(() -> notFound(id));
		return create(actor, new CreateShiftRequest(
				s.title(), s.description(), newDate, s.startTime(), s.endTime(), s.location(),
				s.capacity(), s.reminderOffsetsMinutes()));
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

	// ---------------------------------------------------------------------

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
				toInstant(rs.getObject("created_at", OffsetDateTime.class)));
	}

	private static final String SELECT = """
			SELECT s.id, s.title, s.description, s.shift_date, s.start_time, s.end_time, s.location,
				   s.capacity, s.reminder_offsets_minutes, s.status, s.cancel_reason, s.created_at,
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
