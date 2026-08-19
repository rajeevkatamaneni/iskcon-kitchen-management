package org.iskcon.kms.staff;

import java.util.List;

/** A staff member's record and schedule (E6-S1, E6-S8): who they are, the 7-day template, and any date exceptions. */
public record StaffProfileDetailView(
		StaffProfileView profile,
		List<ScheduleDay> template,
		List<ScheduleExceptionView> exceptions) {
}
