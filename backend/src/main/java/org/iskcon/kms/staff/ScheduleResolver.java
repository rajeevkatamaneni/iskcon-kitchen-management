package org.iskcon.kms.staff;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The one place that answers "is this person in, on this date, and for what hours?".
 *
 * <p>It exists because the answer is now read by two things that must never disagree: the week grid
 * a manager edits, and the head count the Today tile and the planner pebbles show. Writing the rule
 * twice is how a screen comes to say four cooks while the one beside it says three, and neither is
 * wrong about anything except the other.
 *
 * <p>The rule, in order:
 *
 * <ol>
 *   <li>the weekly template says what a person's ordinary week is;
 *   <li>a per-date override replaces it for that date alone — changed hours, an added day, or half
 *       of a swap;
 *   <li>approved leave takes precedence over both, because leave is the answer to "why is this
 *       person not in", and a roster that schedules over it is a roster nobody trusts.
 * </ol>
 *
 * <p>Only <em>approved</em> leave is read here. A request still waiting is not yet an absence: the
 * cook is expected in until somebody says otherwise, and a grid that emptied itself the moment
 * somebody asked would let anyone take a day off by requesting it.
 *
 * <p>Half-day leave leaves the person on the grid and out of the head count (item 19). The grid still
 * shows the name, still marked half day, because somebody looking for who is around today must be
 * able to find them. The count says zero, for two reasons that hold independently. An extra pair of
 * hands does not hurt; being short when you need more does, and that asymmetry decides every close
 * call here. And the record does not say <em>which</em> half: {@code half_day} is a boolean with no
 * time beside it, so counting the person as available claims a certainty the record does not hold.
 * They may be gone by noon, and lunch is the meal that needed them.
 *
 * <p>The count also has a meal grain. A person counts towards a meal if their working window covers
 * that meal's ready-by time, so somebody on 06:00–14:00 is a pair of hands at breakfast and at lunch
 * and none at all at dinner. The window is always there to be read: the schema refuses a working day
 * without both ends of it.
 */
@Component
class ScheduleResolver {

	private final JdbcTemplate jdbc;

	ScheduleResolver(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** One person on one date, with everything the grid needs to draw it and to undo it. */
	record ResolvedShift(
			LocalDate date,
			int dayOfWeek,
			boolean working,
			LocalTime startTime,
			LocalTime endTime,
			boolean fromException,
			UUID exceptionId,
			UUID swapLinkId,
			UUID leaveId,
			LeaveType leaveType,
			boolean halfDayLeave) {

		/**
		 * Whether this day puts a body in the kitchen. A half day does not: see the note on the class
		 * for why zero is the only number the record supports. The grid reads {@link #working()} and
		 * so still draws them; only the count reads this.
		 */
		boolean countsAsIn() {
			return working && !halfDayLeave;
		}

		/**
		 * Whether they are here when a meal's food must be ready. A null time asks about the whole
		 * day rather than a moment, which is what the foot of a week-grid column wants.
		 *
		 * <p>Both ends are inclusive. Somebody rostered until 14:00 is in the kitchen at 14:00, and a
		 * meal due on the hour they leave is a meal they are there for.
		 */
		boolean covers(LocalTime readyBy) {
			if (readyBy == null) {
				return true;
			}
			return startTime != null && endTime != null
					&& !readyBy.isBefore(startTime) && !readyBy.isAfter(endTime);
		}
	}

	/**
	 * The resolved grid for a range: the active staff in name order, and each one's days by date.
	 * Both callers want the staff list as well as the days, so it is returned once rather than
	 * queried again on the other side.
	 */
	record Resolution(List<StaffProfileView> staff, Map<UUID, Map<LocalDate, ResolvedShift>> days) {

		/** How many are in on one date — the figure at the foot of the column. */
		int staffIn(LocalDate date) {
			return staffIn(date, null, null);
		}

		/**
		 * How many are in for one meal: the people whose working window covers the time that meal's
		 * food must be ready. Someone on 06:00–14:00 is counted for breakfast and for lunch and not
		 * for a dinner due at 18:30, which is the whole point of asking the question per meal rather
		 * than per day. A null time asks about the whole day.
		 *
		 * <p>{@code away} names one person to leave out — what the figure would read if their leave
		 * were approved. Told to the approver, never used to stop them.
		 */
		int staffIn(LocalDate date, LocalTime readyBy, UUID away) {
			int in = 0;
			for (StaffProfileView person : staff) {
				if (away != null && away.equals(person.id())) {
					continue;
				}
				ResolvedShift shift = days.getOrDefault(person.id(), Map.of()).get(date);
				if (shift != null && shift.countsAsIn() && shift.covers(readyBy)) {
					in++;
				}
			}
			return in;
		}
	}

	/**
	 * Resolves every active staff member across the inclusive range.
	 *
	 * <p>Former staff are absent by construction. Somebody who left in March should not appear on
	 * April's grid at all, and counting their old template as a body in the kitchen would be worse
	 * than merely untidy.
	 */
	Resolution resolve(LocalDate from, LocalDate to) {
		List<StaffProfileView> staff = jdbc.query(
				StaffEmploymentService.SELECT + " WHERE sp.employment_status = 'ACTIVE' ORDER BY sp.full_name",
				StaffEmploymentService.MAPPER);

		Map<UUID, Map<Integer, ScheduleDay>> templates = new LinkedHashMap<>();
		jdbc.query("""
				SELECT staff_profile_id, day_of_week, working, start_time, end_time
				FROM staff_schedule_template
				""", rs -> {
			UUID profileId = rs.getObject("staff_profile_id", UUID.class);
			templates.computeIfAbsent(profileId, k -> new LinkedHashMap<>()).put(
					rs.getInt("day_of_week"),
					new ScheduleDay(rs.getInt("day_of_week"), rs.getBoolean("working"),
							rs.getObject("start_time", LocalTime.class),
							rs.getObject("end_time", LocalTime.class)));
		});

		Map<UUID, Map<LocalDate, DayOverride>> overrides = new LinkedHashMap<>();
		jdbc.query("""
				SELECT id, staff_profile_id, exception_date, working, start_time, end_time, note, swap_link_id
				FROM staff_schedule_exceptions WHERE exception_date BETWEEN ? AND ?
				""", rs -> {
			UUID profileId = rs.getObject("staff_profile_id", UUID.class);
			overrides.computeIfAbsent(profileId, k -> new LinkedHashMap<>()).put(
					rs.getObject("exception_date", LocalDate.class),
					new DayOverride(rs.getObject("id", UUID.class), rs.getBoolean("working"),
							rs.getObject("start_time", LocalTime.class),
							rs.getObject("end_time", LocalTime.class),
							rs.getObject("swap_link_id", UUID.class)));
		}, from, to);

		Map<UUID, List<LeaveSpan>> leave = new LinkedHashMap<>();
		jdbc.query("""
				SELECT id, staff_profile_id, leave_type, from_date, to_date, half_day
				FROM staff_leave
				WHERE status = 'APPROVED' AND from_date <= ? AND to_date >= ?
				""", rs -> {
			UUID profileId = rs.getObject("staff_profile_id", UUID.class);
			leave.computeIfAbsent(profileId, k -> new ArrayList<>()).add(
					new LeaveSpan(rs.getObject("id", UUID.class),
							LeaveType.valueOf(rs.getString("leave_type")),
							rs.getObject("from_date", LocalDate.class),
							rs.getObject("to_date", LocalDate.class),
							rs.getBoolean("half_day")));
		}, to, from);

		Map<UUID, Map<LocalDate, ResolvedShift>> resolved = new LinkedHashMap<>();
		for (StaffProfileView person : staff) {
			Map<LocalDate, ResolvedShift> byDate = new LinkedHashMap<>();
			for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
				byDate.put(date, resolveOne(date,
						templates.getOrDefault(person.id(), Map.of()),
						overrides.getOrDefault(person.id(), Map.of()).get(date),
						spanCovering(leave.get(person.id()), date)));
			}
			resolved.put(person.id(), byDate);
		}
		return new Resolution(staff, resolved);
	}

	/** Approved leave covering one date for one person, or null — the schedule guard's question. */
	LeaveSpan approvedLeaveOn(UUID staffProfileId, LocalDate date) {
		List<LeaveSpan> spans = jdbc.query("""
				SELECT id, leave_type, from_date, to_date, half_day FROM staff_leave
				WHERE staff_profile_id = ? AND status = 'APPROVED' AND from_date <= ? AND to_date >= ?
				""", (rs, n) -> new LeaveSpan(rs.getObject("id", UUID.class),
				LeaveType.valueOf(rs.getString("leave_type")),
				rs.getObject("from_date", LocalDate.class),
				rs.getObject("to_date", LocalDate.class),
				rs.getBoolean("half_day")), staffProfileId, date, date);
		return spans.isEmpty() ? null : spans.get(0);
	}

	// ---------------------------------------------------------------------

	private static ResolvedShift resolveOne(
			LocalDate date, Map<Integer, ScheduleDay> template, DayOverride override, LeaveSpan leave) {

		int dayOfWeek = date.getDayOfWeek().getValue();

		boolean working;
		LocalTime start;
		LocalTime end;
		if (override != null) {
			working = override.working();
			start = override.startTime();
			end = override.endTime();
		} else {
			ScheduleDay day = template.get(dayOfWeek);
			working = day != null && day.working();
			start = day == null ? null : day.startTime();
			end = day == null ? null : day.endTime();
		}

		// Full-day leave wins over anything the template or an override says. Half-day leave does
		// not: they are in for part of it, and the hours shown are still the hours they work.
		if (leave != null && !leave.halfDay()) {
			working = false;
			start = null;
			end = null;
		}

		return new ResolvedShift(date, dayOfWeek, working, start, end,
				override != null,
				override == null ? null : override.id(),
				override == null ? null : override.swapLinkId(),
				leave == null ? null : leave.id(),
				leave == null ? null : leave.leaveType(),
				leave != null && leave.halfDay());
	}

	private static LeaveSpan spanCovering(List<LeaveSpan> spans, LocalDate date) {
		if (spans == null) {
			return null;
		}
		for (LeaveSpan span : spans) {
			if (!date.isBefore(span.fromDate()) && !date.isAfter(span.toDate())) {
				return span;
			}
		}
		return null;
	}

	/** Approved leave as the resolver needs it: the span, its kind, and whether it is a half day. */
	record LeaveSpan(UUID id, LeaveType leaveType, LocalDate fromDate, LocalDate toDate, boolean halfDay) {
	}

	// Not named Override: a nested type of that name shadows java.lang.Override everywhere in this
	// file, and the compiler's complaint about an annotation that is not an annotation type takes
	// longer to read than it does to choose a different word.
	private record DayOverride(UUID id, boolean working, LocalTime startTime, LocalTime endTime, UUID swapLinkId) {
	}
}
