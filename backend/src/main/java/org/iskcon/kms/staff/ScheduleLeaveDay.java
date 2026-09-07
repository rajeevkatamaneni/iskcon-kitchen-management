package org.iskcon.kms.staff;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One date somebody has approved leave on, as their own schedule screen needs to read it (T-032).
 *
 * <p>It is a <em>date</em> and not a span on purpose. {@code staff_leave} stores a from/to pair, and
 * a screen handed that pair would have to walk it out into days itself — which is the resolution
 * order restated in a second place, in a language where nobody would think to look for it. The order
 * lives once, in {@link ScheduleResolver}, and this record is what comes out of it: the manager's
 * grid and the cook's list ask the same code the same question and therefore cannot disagree about
 * one Thursday.
 *
 * <p>The label is printed here rather than in the browser for the reason {@link LeaveType#label()}
 * gives — a second copy of the vocabulary drifts the first time a word changes.
 *
 * <p>{@code halfDayLeave} is the case a client-side reimplementation gets wrong. A half day leaves
 * the person in for part of it, so the hours for that date still stand and the screen shows both;
 * only a full day replaces them. That asymmetry is stated on {@link ScheduleResolver} and is not
 * restated in TypeScript.
 */
public record ScheduleLeaveDay(
		LocalDate date,
		UUID leaveId,
		LeaveType leaveType,
		String leaveLabel,
		boolean halfDayLeave) {
}
