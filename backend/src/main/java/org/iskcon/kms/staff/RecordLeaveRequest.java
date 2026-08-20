package org.iskcon.kms.staff;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * The temple recording leave for one of its staff (B7).
 *
 * <p>Its own request rather than a flag on {@link RequestLeaveRequest}, and the difference is not
 * cosmetic: this one names the staff member and lands <em>already approved</em>. A janitor has no
 * app to ask from, so the admin or manager writes it down — and the person approving and the person
 * recording are the same person in the same act, which makes a PENDING row a queue entry waiting
 * for somebody to answer themselves. Two shapes, because they are two different acts, and folding
 * them into one endpoint with a boolean would put "approve this on my own authority" one mistyped
 * field away from the self-service form.
 *
 * <p>It is also what the week grid's "mark them off" posts. Marking somebody off <em>is</em> a leave
 * record (build brief §4, "One concept, not two"), so the grid has no separate write of its own.
 */
public record RecordLeaveRequest(
		@NotNull java.util.UUID staffProfileId,
		@NotNull LeaveType leaveType,
		@NotNull LocalDate fromDate,
		@NotNull LocalDate toDate,
		boolean halfDay,
		@Size(max = 500) String reason,
		/** What the approver wants on the record — "rang in, fever" — rather than why it was asked. */
		@Size(max = 500) String decisionNote) {
}
