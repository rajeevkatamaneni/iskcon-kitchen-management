package org.iskcon.kms.staff;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * A staff member asking for time off from their own account page (B7).
 *
 * <p>No staff profile is named: it is theirs, resolved from the verified token, exactly as tenant
 * is. A request that let the caller say whose leave it was would be a request that let them ask on
 * somebody else's behalf.
 *
 * <p>Nothing here is checked against today. Back-dating is the ordinary case for sick leave and
 * refusing it would only teach people to record the wrong dates.
 */
public record RequestLeaveRequest(
		@NotNull(message = "Choose the kind of leave.") LeaveType leaveType,
		@NotNull(message = "Enter the first day you will be away.") LocalDate fromDate,
		@NotNull(message = "Enter the last day you will be away.") LocalDate toDate,
		boolean halfDay,
		@Size(max = 500, message = "That reason is too long.") String reason) {
}
