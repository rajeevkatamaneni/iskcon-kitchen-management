package org.iskcon.kms.staff;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
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
 * Staff profiles and their weekly schedule (E6-S1, reworked by B7 §6). A profile carries a recurring
 * 7-day template; a single date is overridden without touching it. The resolution order — approved
 * leave, then the override, then the template — is stated once, in {@link ScheduleResolver}, because
 * the head count reads it too.
 *
 * <p><strong>Overrides are the week grid's, not the template page's.</strong> Since the 2026-08-20
 * brief they are written by clicking a cell on the grid, and the four things that cell can do are
 * the four things below: change the hours, add somebody on, swap two days, or mark them off. The
 * last of those is not here at all — marking somebody off is a leave record ({@link LeaveService}),
 * because there must be exactly one answer to "why is this person not in on Thursday".
 *
 * <p>What is left of "not working" as an override is precisely one thing: the outbound half of a
 * swap, where the person is not absent but in on a different day. The database says so too, in
 * V62's {@code staff_exception_off_only_as_a_swap}.
 *
 * <p><strong>No overtime, anywhere.</strong> Adding a salaried cook to an extra day changes the
 * roster and nothing else. There is no hours ledger, no rate and no multiplier here on purpose: the
 * temple pays a monthly salary (build brief §7), and a system that quietly accumulated extra hours
 * would be making a promise about pay that nobody agreed to.
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
	private final ScheduleResolver resolver;
	private final WorkforceService workforce;

	public StaffScheduleService(JdbcTemplate jdbc, NotificationService notificationService,
			ScheduleResolver resolver, WorkforceService workforce) {
		this.jdbc = jdbc;
		this.notificationService = notificationService;
		this.resolver = resolver;
		this.workforce = workforce;
	}

	// ---- Profiles -------------------------------------------------------


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

	/**
	 * Sets one date's hours, whether or not the template has them working that day (B7 §6, actions 1
	 * and 3). "Change the hours" and "add them on" are the same write, deliberately: both say this
	 * person works these hours on this date, and giving them two endpoints would give them two
	 * chances to disagree.
	 *
	 * <p>Refuses {@code working = false}. A day somebody is not in is leave, recorded and approved by
	 * whoever marked them off ({@link LeaveService#recordOnBehalf}); the only override that says "not
	 * working" is the outbound half of a swap, which {@link #swap} writes with its sibling. Refusing
	 * loudly rather than quietly turning it into leave is on purpose — the caller has asked for a
	 * different thing from the one that would happen.
	 *
	 * <p>Returns the affected staff member's user id.
	 */
	@Transactional
	public UUID setException(UUID profileId, SetScheduleExceptionRequest request) {
		UUID userId = requireProfileUser(profileId);
		if (!request.working()) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of(
					"field", "working",
					"reason", "a day off is a leave record, not a schedule override"));
		}
		validateHours(true, request.startTime(), request.endTime());
		refuseIfOnApprovedLeave(profileId, request.exceptionDate());
		writeOverride(profileId, request.exceptionDate(), true,
				request.startTime(), request.endTime(), trimToNull(request.note()), null);
		return userId;
	}

	/**
	 * Moves one working day to another date (B7 §6, action 4). Both halves are written here, in one
	 * transaction, sharing a link id — this is the case people get wrong by doing half of it, leaving
	 * a cook marked off Thursday and never added to Saturday, which reads on the grid as somebody who
	 * simply vanished.
	 *
	 * <p>The hours travel with them: whatever they were going to work on the day being given up is
	 * what they work on the day they take instead. Changing the hours as well is a second, separate
	 * act on the grid, and keeping it separate is what makes "put that back" mean one thing.
	 */
	@Transactional
	public UUID swap(UUID profileId, SwapShiftRequest request) {
		UUID userId = requireProfileUser(profileId);
		if (request.fromDate().equals(request.toDate())) {
			throw new ApplicationException(ErrorCode.SWAP_NEEDS_TWO_DAYS,
					Map.of("date", request.fromDate()));
		}
		refuseIfOnApprovedLeave(profileId, request.fromDate());
		refuseIfOnApprovedLeave(profileId, request.toDate());

		// The day being given up has to be one they were actually going to work; there is nothing to
		// move otherwise, and the grid would end up showing two days off and a day added from nowhere.
		ScheduleResolver.ResolvedShift giving = resolveOne(profileId, request.fromDate());
		// Null means they are no longer actively employed and so appear on no grid at all. Either
		// way there is no shift on that date to move, and the sentence below is the true one.
		if (giving == null || !giving.working()) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of(
					"field", "fromDate",
					"reason", "they are not down to work that day, so there is nothing to move"));
		}

		UUID link = UUID.randomUUID();
		String note = trimToNull(request.note());
		writeOverride(profileId, request.fromDate(), false, null, null, note, link);
		writeOverride(profileId, request.toDate(), true,
				giving.startTime(), giving.endTime(), note, link);
		return userId;
	}

	/**
	 * Removes one override, reverting that date to the template; returns the affected user id.
	 *
	 * <p>Undoing half of a swap undoes all of it. The link id is what makes that possible, and it is
	 * the whole reason V62 added the column: a manager who puts Saturday back and is left with a cook
	 * still missing from Thursday has been given a broken roster by the undo, not by the swap.
	 */
	@Transactional
	public UUID deleteException(UUID profileId, UUID exceptionId) {
		UUID userId = requireProfileUser(profileId);
		List<UUID> links = jdbc.queryForList(
				"SELECT swap_link_id FROM staff_schedule_exceptions WHERE id = ? AND staff_profile_id = ?",
				UUID.class, exceptionId, profileId);
		if (links.isEmpty()) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("exceptionId", exceptionId));
		}
		UUID link = links.get(0);
		if (link == null) {
			jdbc.update("DELETE FROM staff_schedule_exceptions WHERE id = ?", exceptionId);
		} else {
			jdbc.update("DELETE FROM staff_schedule_exceptions WHERE swap_link_id = ?", link);
		}
		return userId;
	}

	private void writeOverride(UUID profileId, LocalDate date, boolean working,
			LocalTime startTime, LocalTime endTime, String note, UUID swapLinkId) {

		jdbc.update("""
				INSERT INTO staff_schedule_exceptions (
					id, tenant_id, staff_profile_id, exception_date, working, start_time, end_time,
					note, swap_link_id)
				VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid,
					?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (tenant_id, staff_profile_id, exception_date)
				DO UPDATE SET working = EXCLUDED.working, start_time = EXCLUDED.start_time,
					end_time = EXCLUDED.end_time, note = EXCLUDED.note,
					-- Kept rather than overwritten, so that correcting the hours of a day somebody
					-- swapped onto does not quietly sever it from the day they swapped out of and
					-- leave an undo that puts back only half.
					swap_link_id = COALESCE(EXCLUDED.swap_link_id, staff_schedule_exceptions.swap_link_id)
				""", profileId, date, working, startTime, endTime, note, swapLinkId);
	}

	/**
	 * Approved leave is not something the roster may write over (KMS-4956). The manager revokes it
	 * first if the person is in after all — which is a decision with a name and a time on it, rather
	 * than a cell quietly overwritten and an absence nobody can account for afterwards.
	 */
	private void refuseIfOnApprovedLeave(UUID profileId, LocalDate date) {
		if (resolver.approvedLeaveOn(profileId, date) != null) {
			throw new ApplicationException(ErrorCode.CANNOT_SCHEDULE_OVER_LEAVE,
					Map.of("staffProfileId", profileId, "date", date));
		}
	}

	private ScheduleResolver.ResolvedShift resolveOne(UUID profileId, LocalDate date) {
		return resolver.resolve(date, date).days().getOrDefault(profileId, Map.of()).get(date);
	}

	// ---- Week view ------------------------------------------------------

	/**
	 * The seven-column grid: who is in, why they are not, and how many of them there are.
	 *
	 * <p>Every day here comes from {@link ScheduleResolver} and every column total from {@link
	 * WorkforceService}, both of which the Today tile and the planner pebbles read as well. This
	 * screen deliberately computes nothing of its own — a grid that added up its own columns would be
	 * a fourth opinion about how many cooks there are, and the fourth opinion is always the one
	 * somebody is standing in the kitchen looking at.
	 */
	@Transactional(readOnly = true)
	public WeekScheduleView weekView(LocalDate weekStart) {
		LocalDate weekEnd = weekStart.plusDays(6);
		ScheduleResolver.Resolution resolution = resolver.resolve(weekStart, weekEnd);

		List<WeekScheduleView.StaffWeek> rows = new ArrayList<>();
		for (StaffProfileView p : resolution.staff()) {
			Map<LocalDate, ScheduleResolver.ResolvedShift> resolved =
					resolution.days().getOrDefault(p.id(), Map.of());
			List<WeekScheduleView.ResolvedDay> days = new ArrayList<>();
			for (int i = 0; i < 7; i++) {
				ScheduleResolver.ResolvedShift shift = resolved.get(weekStart.plusDays(i));
				days.add(new WeekScheduleView.ResolvedDay(
						shift.date(), shift.dayOfWeek(), shift.working(),
						shift.startTime(), shift.endTime(),
						shift.fromException(), shift.exceptionId(), shift.swapLinkId(),
						shift.leaveId(), shift.leaveType(),
						shift.leaveType() == null ? null : shift.leaveType().label(),
						shift.halfDayLeave()));
			}
			rows.add(new WeekScheduleView.StaffWeek(p.id(), p.userId(), p.fullName(), p.jobTitleLabel(), days));
		}
		return new WeekScheduleView(weekStart, rows,
				List.copyOf(workforce.countFor(weekStart, weekEnd, resolution).values()));
	}

	// ---- Notification (called by the controller, its own transaction) ---

	/**
	 * Best-effort: a schedule-change notice to the affected staff member. Never fails the change.
	 *
	 * <p>Silent for staff who hold no app account (E6-S8) — there is nobody to notify, and their
	 * hours are told to them the way they always were.
	 */
	public void notifyScheduleChange(UUID userId) {
		if (userId == null) {
			return;
		}
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

	/**
	 * The affected staff member's user id, or null when they hold no account — the profile still has
	 * to exist, which is what this actually guards.
	 */
	private UUID requireProfileUser(UUID profileId) {
		return findProfile(profileId).orElseThrow(() -> notFound(profileId)).userId();
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

	// One shape for a member of staff, owned by StaffEmploymentService. Two spellings of the same
	// row is how a left join in one place and an inner join in the other quietly disagree about
	// whether staff without a login exist.
	private static final String PROFILE_SELECT = StaffEmploymentService.SELECT;

	private static final RowMapper<StaffProfileView> PROFILE_MAPPER = StaffEmploymentService.MAPPER;
}
