package org.iskcon.kms.staff;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
 * Staff profiles and their weekly schedule (E6-S1). A profile carries a recurring 7-day template; a
 * single date is overridden with an exception, leaving the template untouched. The resolved schedule
 * for a date is its exception if one exists, otherwise the template for that weekday.
 *
 * <p>The mutations return the affected staff member's user id so the controller can send the
 * change notification in its own transaction — a notification that can't be queued must never roll
 * back the schedule change that prompted it.
 */
@Service
public class StaffScheduleService {

	private static final Logger log = LoggerFactory.getLogger(StaffScheduleService.class);

	private final JdbcTemplate jdbc;
	private final NotificationService notificationService;

	public StaffScheduleService(JdbcTemplate jdbc, NotificationService notificationService) {
		this.jdbc = jdbc;
		this.notificationService = notificationService;
	}

	// ---- Profiles -------------------------------------------------------

	@Transactional(readOnly = true)
	public List<StaffProfileView> listProfiles() {
		return jdbc.query(PROFILE_SELECT + " ORDER BY u.full_name", PROFILE_MAPPER);
	}

	@Transactional
	public UUID createProfile(CreateStaffProfileRequest request) {
		String role = jdbc.query("SELECT role FROM users WHERE id = ?",
				(rs, n) -> rs.getString("role"), request.userId()).stream().findFirst()
				.orElseThrow(() -> new ApplicationException(
						ErrorCode.RESOURCE_NOT_FOUND, Map.of("userId", request.userId())));
		if (!"KITCHEN_STAFF".equals(role)) {
			throw new ApplicationException(ErrorCode.USER_NOT_KITCHEN_STAFF, Map.of("userId", request.userId()));
		}
		Integer existing = jdbc.queryForObject(
				"SELECT count(*) FROM staff_profiles WHERE user_id = ?", Integer.class, request.userId());
		if (existing != null && existing > 0) {
			throw new ApplicationException(ErrorCode.STAFF_PROFILE_ALREADY_EXISTS, Map.of("userId", request.userId()));
		}

		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO staff_profiles (id, tenant_id, user_id, designation)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?)
				""", id, request.userId(), trimToNull(request.designation()));
		// Seed a full week of days-off so the grid always has all seven days to edit.
		for (int day = 1; day <= 7; day++) {
			jdbc.update("""
					INSERT INTO staff_schedule_template (id, tenant_id, staff_profile_id, day_of_week, working)
					VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, false)
					""", id, day);
		}
		return id;
	}

	@Transactional
	public void updateProfile(UUID id, UpdateStaffProfileRequest request) {
		int updated = jdbc.update("""
				UPDATE staff_profiles SET designation = ?, active = ?, updated_at = now() WHERE id = ?
				""", trimToNull(request.designation()), request.active(), id);
		if (updated == 0) {
			throw notFound(id);
		}
	}

	@Transactional(readOnly = true)
	public StaffProfileDetailView getProfile(UUID id) {
		StaffProfileView profile = findProfile(id).orElseThrow(() -> notFound(id));
		return new StaffProfileDetailView(profile, templateFor(id), exceptionsFor(id));
	}

	@Transactional(readOnly = true)
	public Optional<StaffProfileDetailView> scheduleForUser(UUID userId) {
		return jdbc.query(PROFILE_SELECT + " WHERE sp.user_id = ?", PROFILE_MAPPER, userId).stream()
				.findFirst()
				.map(p -> new StaffProfileDetailView(p, templateFor(p.id()), exceptionsFor(p.id())));
	}

	// ---- Template & exceptions -----------------------------------------

	/** Replaces the weekly template; returns the affected staff member's user id. */
	@Transactional
	public UUID setTemplate(UUID profileId, SetScheduleTemplateRequest request) {
		UUID userId = requireProfileUser(profileId);
		for (SetScheduleTemplateRequest.Entry e : request.days()) {
			validateHours(e.working(), e.startTime(), e.endTime());
			jdbc.update("""
					INSERT INTO staff_schedule_template (
						id, tenant_id, staff_profile_id, day_of_week, working, start_time, end_time)
					VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?)
					ON CONFLICT (tenant_id, staff_profile_id, day_of_week)
					DO UPDATE SET working = EXCLUDED.working, start_time = EXCLUDED.start_time,
						end_time = EXCLUDED.end_time
					""", profileId, e.dayOfWeek(), e.working(),
					e.working() ? e.startTime() : null, e.working() ? e.endTime() : null);
		}
		return userId;
	}

	/** Sets (or replaces) one date's exception; returns the affected staff member's user id. */
	@Transactional
	public UUID setException(UUID profileId, SetScheduleExceptionRequest request) {
		UUID userId = requireProfileUser(profileId);
		validateHours(request.working(), request.startTime(), request.endTime());
		jdbc.update("""
				INSERT INTO staff_schedule_exceptions (
					id, tenant_id, staff_profile_id, exception_date, working, start_time, end_time, note)
				VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (tenant_id, staff_profile_id, exception_date)
				DO UPDATE SET working = EXCLUDED.working, start_time = EXCLUDED.start_time,
					end_time = EXCLUDED.end_time, note = EXCLUDED.note
				""", profileId, request.exceptionDate(), request.working(),
				request.working() ? request.startTime() : null,
				request.working() ? request.endTime() : null, trimToNull(request.note()));
		return userId;
	}

	/** Removes one exception, reverting that date to the template; returns the affected user id. */
	@Transactional
	public UUID deleteException(UUID profileId, UUID exceptionId) {
		UUID userId = requireProfileUser(profileId);
		int deleted = jdbc.update(
				"DELETE FROM staff_schedule_exceptions WHERE id = ? AND staff_profile_id = ?",
				exceptionId, profileId);
		if (deleted == 0) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("exceptionId", exceptionId));
		}
		return userId;
	}

	// ---- Week view ------------------------------------------------------

	@Transactional(readOnly = true)
	public WeekScheduleView weekView(LocalDate weekStart) {
		List<StaffProfileView> profiles = jdbc.query(
				PROFILE_SELECT + " WHERE sp.active = true ORDER BY u.full_name", PROFILE_MAPPER);
		LocalDate weekEnd = weekStart.plusDays(6);

		// template[profileId][dayOfWeek] and exception[profileId][date], resolved per day below.
		Map<UUID, Map<Integer, ScheduleDay>> templates = new LinkedHashMap<>();
		jdbc.query("""
				SELECT staff_profile_id, day_of_week, working, start_time, end_time
				FROM staff_schedule_template
				""", rs -> {
			UUID pid = rs.getObject("staff_profile_id", UUID.class);
			templates.computeIfAbsent(pid, k -> new LinkedHashMap<>()).put(
					rs.getInt("day_of_week"),
					new ScheduleDay(rs.getInt("day_of_week"), rs.getBoolean("working"),
							rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class)));
		});
		Map<UUID, Map<LocalDate, ScheduleExceptionView>> exceptions = new LinkedHashMap<>();
		jdbc.query("""
				SELECT id, staff_profile_id, exception_date, working, start_time, end_time, note
				FROM staff_schedule_exceptions WHERE exception_date BETWEEN ? AND ?
				""", rs -> {
			UUID pid = rs.getObject("staff_profile_id", UUID.class);
			exceptions.computeIfAbsent(pid, k -> new LinkedHashMap<>()).put(
					rs.getObject("exception_date", LocalDate.class),
					new ScheduleExceptionView(rs.getObject("id", UUID.class),
							rs.getObject("exception_date", LocalDate.class), rs.getBoolean("working"),
							rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class),
							rs.getString("note")));
		}, weekStart, weekEnd);

		List<WeekScheduleView.StaffWeek> rows = new ArrayList<>();
		for (StaffProfileView p : profiles) {
			List<WeekScheduleView.ResolvedDay> days = new ArrayList<>();
			for (int i = 0; i < 7; i++) {
				LocalDate date = weekStart.plusDays(i);
				int dow = date.getDayOfWeek().getValue();
				ScheduleExceptionView ex = exceptions.getOrDefault(p.id(), Map.of()).get(date);
				if (ex != null) {
					days.add(new WeekScheduleView.ResolvedDay(
							date, dow, ex.working(), ex.startTime(), ex.endTime(), true));
				} else {
					ScheduleDay t = templates.getOrDefault(p.id(), Map.of()).get(dow);
					days.add(new WeekScheduleView.ResolvedDay(
							date, dow, t != null && t.working(),
							t == null ? null : t.startTime(), t == null ? null : t.endTime(), false));
				}
			}
			rows.add(new WeekScheduleView.StaffWeek(p.id(), p.userId(), p.fullName(), p.designation(), days));
		}
		return new WeekScheduleView(weekStart, rows);
	}

	// ---- Notification (called by the controller, its own transaction) ---

	/** Best-effort: a schedule-change notice to the affected staff member. Never fails the change. */
	public void notifyScheduleChange(UUID userId) {
		try {
			String templeName = jdbc.queryForObject("""
					SELECT name FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""", String.class);
			String fullName = jdbc.queryForObject("SELECT full_name FROM users WHERE id = ?", String.class, userId);
			notificationService.notify(
					NotificationRecipient.user(userId),
					NotificationTemplate.STAFF_SCHEDULE_UPDATED,
					Map.of("name", fullName == null ? "" : fullName, "temple", templeName == null ? "" : templeName),
					null);
		} catch (RuntimeException e) {
			log.warn("Could not queue a schedule-change notice for staff {}: {}", userId, e.toString());
		}
	}

	// ---------------------------------------------------------------------

	private List<ScheduleDay> templateFor(UUID profileId) {
		return jdbc.query("""
				SELECT day_of_week, working, start_time, end_time
				FROM staff_schedule_template WHERE staff_profile_id = ? ORDER BY day_of_week
				""", (rs, n) -> new ScheduleDay(rs.getInt("day_of_week"), rs.getBoolean("working"),
				rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class)), profileId);
	}

	private List<ScheduleExceptionView> exceptionsFor(UUID profileId) {
		return jdbc.query("""
				SELECT id, exception_date, working, start_time, end_time, note
				FROM staff_schedule_exceptions WHERE staff_profile_id = ? ORDER BY exception_date
				""", (rs, n) -> new ScheduleExceptionView(rs.getObject("id", UUID.class),
				rs.getObject("exception_date", LocalDate.class), rs.getBoolean("working"),
				rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class),
				rs.getString("note")), profileId);
	}

	private UUID requireProfileUser(UUID profileId) {
		return findProfile(profileId).map(StaffProfileView::userId).orElseThrow(() -> notFound(profileId));
	}

	private Optional<StaffProfileView> findProfile(UUID id) {
		return jdbc.query(PROFILE_SELECT + " WHERE sp.id = ?", PROFILE_MAPPER, id).stream().findFirst();
	}

	private static void validateHours(boolean working, LocalTime start, LocalTime end) {
		if (working && (start == null || end == null || !end.isAfter(start))) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "hours", "reason", "a working day needs a start before its end"));
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
		return new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("staffProfileId", id));
	}

	private static final String PROFILE_SELECT = """
			SELECT sp.id, sp.user_id, u.full_name, sp.designation, sp.active, sp.created_at
			FROM staff_profiles sp JOIN users u ON u.id = sp.user_id
			""";

	private static final RowMapper<StaffProfileView> PROFILE_MAPPER = (rs, n) -> new StaffProfileView(
			rs.getObject("id", UUID.class),
			rs.getObject("user_id", UUID.class),
			rs.getString("full_name"),
			rs.getString("designation"),
			rs.getBoolean("active"),
			toInstant(rs.getObject("created_at", OffsetDateTime.class)));

	private static java.time.Instant toInstant(OffsetDateTime odt) {
		return odt == null ? null : odt.toInstant();
	}
}
