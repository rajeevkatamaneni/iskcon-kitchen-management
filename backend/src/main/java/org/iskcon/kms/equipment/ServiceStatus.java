package org.iskcon.kms.equipment;

/**
 * Where a piece of equipment stands against its next service (E3-S10 D5). Derived at read time from
 * the interval, the newest service and the temple's own warning horizon — never stored.
 *
 * <p>Two warning states rather than one, and the amber one is the one that does the work. Red on the
 * morning a service falls due is a fire alarm: the point of the feature is to book the engineer while
 * there is still time.
 */
public enum ServiceStatus {

	/** Due, but further off than the temple's warning horizon. Nothing to do yet. */
	OK,

	/** Due within the temple's warning horizon. Amber — book the engineer now. */
	DUE_SOON,

	/** The date has passed. Red. */
	OVERDUE,

	/**
	 * No interval, or no date to count from, or scrapped. Reads "not scheduled" and appears in no
	 * warning count — a machine nobody has decided about is not a machine that is late (D6).
	 *
	 * <p>Since V122 this means what it always should have meant and nothing more: <em>nobody has
	 * decided about this yet.</em> A thing that will never need deciding about reads
	 * {@link #NOT_SERVICED} instead.
	 */
	NOT_SCHEDULED,

	/**
	 * Somebody has said this never needs servicing — a ladder, a trestle table (T-120).
	 *
	 * <p>The distinction from {@link #NOT_SCHEDULED} is the whole point of the state and it is a
	 * distinction about people, not about machines. Both are invisible to every warning count, so
	 * neither nags; but one of them is a job somebody still has to do and the other is finished. A
	 * register that says "not scheduled" against sixty stools for ever teaches its reader that the
	 * phrase means nothing, and then it means nothing when a boiler is wearing it.
	 *
	 * <p>Derived from {@code equipment_items.never_needs_servicing}, which V122's CHECK constraint
	 * keeps mutually exclusive with an interval — so this state can never hide a schedule.
	 */
	NOT_SERVICED
}
