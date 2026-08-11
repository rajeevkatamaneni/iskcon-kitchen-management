package org.iskcon.kms.staff;

import java.util.List;

/** A staff member's full schedule (E6-S1): profile, the 7-day template, and any date exceptions. */
public record StaffProfileDetailView(
		StaffProfileView profile,
		List<ScheduleDay> template,
		List<ScheduleExceptionView> exceptions) {
}
