package org.iskcon.kms.staff;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One day of the schedule with what it <em>needs</em> beside what it <em>has</em> (E6-S15).
 *
 * <p>The staff schedule used to show supply only — seven columns of hours and a foot that read
 * <em>In that day · 4 staff, 2 volunteers</em>. Both halves of the answer existed in the system and
 * had never been put on the same screen: the planner has carried a crew figure per meal since V67,
 * and {@link WorkforceService} has counted the roster against it since E6-S14. This record is the
 * two of them on one line.
 *
 * <p><strong>The shortfall is the deepest meal's, not the day's sum.</strong> A day is short by the
 * worst moment in it: breakfast two short and a fully-crewed lunch is a day two short, at breakfast,
 * and adding the meals' shortfalls together would answer a question nobody asked. The meal that is
 * deepest short travels with the number so the screen can name it rather than leave a manager
 * opening three meals to find out which.
 *
 * @param staffIn    the day's roster, exactly the figure at the foot of the week-grid column —
 *                   {@link WorkforceService} computes it once and this reads it (E6-S11 D5).
 * @param volunteers likewise, and reported apart from staff rather than added to them.
 * @param shortBy    how many people short the worst meal is, or 0. Never negative: a meal with more
 *                   hands than it asked for is covered, not surplus, and there is nothing to draw.
 * @param shortAt    the meal that is deepest short — its name, when it is due, what it asked for and
 *                   what it has. All four are null when the day is not short, because there is then
 *                   no meal they would be about.
 */
public record DayCoverageView(
		LocalDate date,
		int staffIn,
		int volunteers,
		CoverageState state,
		int shortBy,
		String shortAt,
		LocalTime shortAtReadyBy,
		Integer shortAtRequired,
		Integer shortAtRostered) {
}
