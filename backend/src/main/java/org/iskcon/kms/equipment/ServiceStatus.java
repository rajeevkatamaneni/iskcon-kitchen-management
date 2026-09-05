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
	 */
	NOT_SCHEDULED
}
