package org.iskcon.kms.staff;

/**
 * How money reached a member of staff (B8).
 *
 * <p>Three ways, and the difference that matters is whether the payment leaves a trace anywhere
 * else. A cheque has a number and a payroll run has a reference, and both are how somebody
 * reconciling a bank statement months later finds this row again — so neither may be recorded
 * without one. Cash leaves no such trace, and demanding a reference for it would only teach people
 * to type a full stop.
 *
 * <p>An advance is never {@link #PAYROLL}: it is by definition money handed over outside the payroll
 * run, so offering it there would describe something that cannot happen.
 */
public enum PaymentMode {

	CHEQUE("Cheque"),
	CASH("Cash"),
	PAYROLL("Payroll");

	private final String label;

	PaymentMode(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/** True when the payment exists somewhere else too, and this record needs to point at it. */
	public boolean needsReference() {
		return this != CASH;
	}

	/** Advances are handed over directly; a payroll run is the one thing they cannot be. */
	public boolean canPayAnAdvance() {
		return this != PAYROLL;
	}
}
