package org.iskcon.kms.staff;

import jakarta.validation.constraints.Size;

/**
 * The approver's answer (B7). One shape for all three answers — approve, decline, revoke — because
 * the only thing they take is the note, and the verb is the endpoint rather than a field. A body
 * carrying {@code action: "DECLINE"} would let a mistyped string mean approval.
 */
public record DecideLeaveRequest(@Size(max = 500) String note) {
}
