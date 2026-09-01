package org.iskcon.kms.staff;

/**
 * What a day on the schedule has to say about its own staffing (E6-S15).
 *
 * <p>Four states and no fifth, because each one is a different sentence and the screen has to be
 * able to say which. In particular <em>nobody has said how many this takes</em> is not
 * <em>covered</em>: drawing the two the same is how a whole month of unplanned days comes to look
 * reassuring.
 */
public enum CoverageState {

	/** No meal is planned that day, or every dish of every meal was called off. Nothing is owed. */
	NOTHING_PLANNED,

	/**
	 * Meals are planned and not one of them carries a crew figure. The roster cannot be judged
	 * against nothing, and a zero here would be a statement about the world.
	 */
	CREW_NOT_SET,

	/** Every meal that named a number has it. */
	COVERED,

	/** At least one meal names a number the roster does not reach. */
	SHORT
}
