package org.iskcon.kms.staff;

/**
 * The three kinds of leave a temple actually records (B7).
 *
 * <p>Deliberately short. A longer list — casual, earned, compensatory, bereavement — is the
 * vocabulary of a payroll system that accrues balances, and this one does not: nothing here is
 * counted against an entitlement, so a distinction the temple cannot act on would be a field
 * somebody fills in wrongly and nobody ever reads.
 *
 * <p>What the three do carry is a difference the temple can act on. Sick leave is the one that
 * arrives after the fact; unpaid is the one a future pay run must be able to see.
 */
public enum LeaveType {

	TIME_OFF("Time off"),
	SICK("Sick leave"),
	UNPAID("Unpaid leave");

	private final String label;

	LeaveType(String label) {
		this.label = label;
	}

	/** What to print. Served rather than retyped in the browser, where it would drift. */
	public String label() {
		return label;
	}
}
