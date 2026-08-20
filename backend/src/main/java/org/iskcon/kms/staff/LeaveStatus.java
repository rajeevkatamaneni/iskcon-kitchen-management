package org.iskcon.kms.staff;

/**
 * Where one leave record stands (B7).
 *
 * <p>Four values, and only two of them keep somebody off the roster: a record blocks the grid and
 * the workforce count while it is {@link #PENDING} or {@link #APPROVED}, and stops doing so the
 * moment it is answered otherwise. That is the whole state machine, and it is small on purpose —
 * there is no "cancelled by the requester pending approval", because a request nobody has answered
 * yet is simply withdrawn and gone.
 */
public enum LeaveStatus {

	/** Asked for, not yet answered. */
	PENDING,

	/** Granted. This is the one the roster and the head count read. */
	APPROVED,

	/** Refused, with the approver's note. Never re-answered — a fresh request is a fresh record. */
	DECLINED,

	/**
	 * Approved and then taken back — the cook is in after all. Kept rather than deleted: somebody
	 * arranged their week around this, and the fact that it was granted and then withdrawn is
	 * exactly what they will want to point at.
	 */
	REVOKED
}
