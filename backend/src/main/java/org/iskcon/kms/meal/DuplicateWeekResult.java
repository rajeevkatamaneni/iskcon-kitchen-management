package org.iskcon.kms.meal;

/**
 * What duplicating a week actually did (E3).
 *
 * <p>Reported rather than assumed, because the interesting cases are the ones it declined. A planner
 * who presses the button twice, or who had already planned Thursday, needs to be told what was left
 * alone — a bare "done" would leave them wondering whether it worked.
 *
 * @param copied            meals written into the shown week
 * @param daysAlreadyPlanned days left untouched because something was already planned on them
 * @param mealsRefusedOnFast meals not copied because the day they would land on is a fast the recipe
 *                           does not suit — acknowledging that in bulk, for a meal nobody is looking
 *                           at, is not ours to do
 * @param sourceWasEmpty     nothing was planned in the week being copied from
 */
public record DuplicateWeekResult(
		int copied,
		int daysAlreadyPlanned,
		int mealsRefusedOnFast,
		boolean sourceWasEmpty) {
}
