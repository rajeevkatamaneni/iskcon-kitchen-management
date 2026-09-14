package org.iskcon.kms.staff;

/**
 * Where one leave record stands (B7).
 *
 * <p>Five values, and only one of them keeps somebody off the roster: {@link ScheduleResolver} reads
 * {@link #APPROVED} alone. {@link #PENDING} still counts against a second request for the same days,
 * because two overlapping requests from one cook are an approver asked the same question twice. The
 * other three are closed, and nothing reads them except the lists that show them.
 *
 * <p>T-184 added {@link #WITHDRAWN}. This comment used to end "a request nobody has answered yet is
 * simply withdrawn and gone", because withdrawal deleted the row. Rajeev ruled on 2026-09-13 that
 * staff may withdraw approved leave too, before it begins, and that the manager is told, so the row
 * is now kept for the same reason {@link #REVOKED} is.
 */
public enum LeaveStatus {

	/** Asked for, not yet answered. */
	PENDING,

	/** Granted. This is the one the roster and the head count read. */
	APPROVED,

	/** Refused, with the approver's note. Never re-answered — a fresh request is a fresh record. */
	DECLINED,

	/**
	 * Approved and then taken back by the temple — the cook is in after all. Kept rather than deleted:
	 * somebody arranged their week around this, and the fact that it was granted and then taken back
	 * is exactly what they will want to point at.
	 */
	REVOKED,

	/**
	 * Taken back by the person themselves, before its first day in the temple's calendar (T-184).
	 * It may have been pending or approved; an approval stays on the row, in decided_by and
	 * decided_at, and the withdrawal has its own stamp, withdrawn_at (V132). Like every closed status
	 * it is final: a person who still needs the days asks again.
	 */
	WITHDRAWN
}
