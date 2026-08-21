package org.iskcon.kms.meal;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Whether there are enough hands for one meal (item 24) — the readout that reads
 * <em>Rostered · 3 staff · 2 volunteers · 5 of 8</em>.
 *
 * <p>One meal, one line, in four places: the crew pebble on the planner's meal block, the workforce
 * line on Today, the number printed above the names on the job card, and the warning an approver is
 * shown before they grant leave. All four read this record so that none of them can quietly disagree
 * with the others about the same lunch.
 *
 * <p>Staff and volunteers are reported apart and also added. Apart because "we are three short" and
 * "we are three short of staff" are different sentences. Added because the meal itself does not care
 * which: it is satisfied when staff + volunteers reaches the planned number, and splitting that into
 * two requirements would invent a constraint the temple does not have.
 *
 * @param crewRequired how many people the planner said it takes, or null where nobody has said. Null
 *                     is not zero and must not be drawn as a shortfall — a meal is planned weeks
 *                     before anybody is rostered.
 * @param shortOfCrew  a number was set and the roster does not reach it. A quiet warning tone on the
 *                     screen and nothing more: it never blocks saving, and it never blocks leave.
 */
public record MealCrewView(
		LocalDate planDate,
		String mealKind,
		LocalTime readyBy,
		Integer crewRequired,
		int staffIn,
		int volunteers,
		int rostered,
		boolean shortOfCrew) {
}
