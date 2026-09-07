package org.iskcon.kms.staff;

import java.time.LocalDate;
import java.util.List;

/**
 * A staff member's record and schedule (E6-S1, E6-S8): who they are, the 7-day template, any date
 * exceptions, and — where the caller is the person themselves — the approved leave across a window.
 *
 * <p><strong>The leave fields are null for {@code /staff/profiles/{id}}, and that is not a lie about
 * either endpoint.</strong> That endpoint answers a manager's question about somebody's template and
 * resolves no leave; {@code /staff/schedule/me} answers a rostered person's question about their own
 * days ahead and does (T-032). A reader must therefore never treat a null {@link #leaveDays()} as
 * <em>no leave</em>: it means <em>not resolved</em>, and {@link #leaveFrom()}/{@link #leaveTo()} are
 * here so the difference can be told apart rather than guessed at. An empty list inside a window is
 * the only thing that means a clear fortnight.
 */
public record StaffProfileDetailView(
		StaffProfileView profile,
		List<ScheduleDay> template,
		List<ScheduleExceptionView> exceptions,
		List<ScheduleLeaveDay> leaveDays,
		LocalDate leaveFrom,
		LocalDate leaveTo) {
}
