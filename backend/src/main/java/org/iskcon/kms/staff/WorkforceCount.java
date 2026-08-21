package org.iskcon.kms.staff;

import java.time.LocalDate;

/**
 * Who is actually in, on one date. Staff and volunteers are counted separately and never summed —
 * a full-time cook and a two-hour evening volunteer are not interchangeable.
 *
 * <p>This is the one figure three screens read (build brief §6b): the foot of each week-grid column,
 * the Today tile, and the pebbles on the meal planner. Computed once, in {@link WorkforceService},
 * because three screens each working it out for themselves is three screens that disagree by one and
 * nobody able to say which is right.
 */
public record WorkforceCount(LocalDate date, int staffIn, int volunteers) {

	/**
	 * The two added, which is the one question that legitimately adds them: how many pairs of hands
	 * are there to execute a meal (item 24).
	 *
	 * <p>A meal carries the number of people it takes to cook, and at execution time that can be any
	 * mix of staff and volunteers — it is satisfied when staff + volunteers reaches the planned
	 * number. Splitting it into two requirements would invent a constraint the temple does not have.
	 * Every other reader still gets the two figures apart, because "we are three short" and "we are
	 * three short of staff" are different sentences.
	 */
	public int rostered() {
		return staffIn + volunteers;
	}
}
