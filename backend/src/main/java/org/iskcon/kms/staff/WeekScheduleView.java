package org.iskcon.kms.staff;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * The resolved schedule grid for a week (E6-S1, extended by B7 §6): one row per active staff member,
 * seven resolved days each, and the head count at the foot of every column.
 *
 * <p>A resolved day is approved leave if there is any, otherwise the per-date override, otherwise
 * the template for that weekday — so the grid reads correctly even across a month boundary. The
 * order matters and is stated once, in {@link ScheduleResolver}; this record only carries the answer.
 *
 * <p>The counts are here rather than left to the browser because they are the same figures the Today
 * tile and the planner pebbles read (build brief §6b). A grid that added up its own columns would be
 * a fourth opinion about how many cooks there are.
 */
public record WeekScheduleView(
		LocalDate weekStart,
		List<StaffWeek> staff,
		/** One per date, Monday first — staff and volunteers, counted separately and never summed. */
		List<WorkforceCount> counts) {

	public record StaffWeek(
			UUID staffProfileId,
			UUID userId,
			String fullName,
			/** What to print — the temple's own words for the job, or the vocabulary's label. */
			String jobTitleLabel,
			List<ResolvedDay> days) {
	}

	public record ResolvedDay(
			LocalDate date,
			int dayOfWeek,
			boolean working,
			LocalTime startTime,
			LocalTime endTime,

			/** True when a per-date override decided this day, so the grid can show it as adjusted. */
			boolean fromException,
			/** The override's id, for undoing it. Null when the template decided the day. */
			UUID exceptionId,
			/** Shared by the two halves of a swap: undoing either removes both. */
			UUID swapLinkId,

			/**
			 * Approved leave covering this date, if any. The grid draws it read-only and refuses to
			 * schedule over it — a manager who wants them in must revoke the leave first, which is a
			 * decision with a name on it rather than a cell quietly overwritten.
			 */
			UUID leaveId,
			LeaveType leaveType,
			/** What to print for the leave, so the browser keeps no copy of the vocabulary. */
			String leaveLabel,
			/** A half day leaves them in for part of it, so the hours above still stand. */
			boolean halfDayLeave) {
	}
}
