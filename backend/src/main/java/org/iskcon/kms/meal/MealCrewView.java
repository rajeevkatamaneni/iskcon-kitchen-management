package org.iskcon.kms.meal;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

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
 * <p><strong>Since Epic 12 the meal-level figures are its kitchens' added up</strong> — People needed,
 * staff, volunteers and rostered alike — and {@code shortOfCrew} is true when <em>any</em> kitchen is
 * short, not when the sums are. A Main kitchen four short beside a Sweets kitchen three over is a meal
 * that is short: the pastry cooks are not going to make the dal.
 *
 * @param mealId       the meal's own id (D-27), so a reader can open the meal or match a shift to it
 *                     without guessing from its date and kind.
 * @param crewRequired how many people the planner said it takes, or null where nobody has said. Null
 *                     is not zero and must not be drawn as a shortfall — a meal is planned weeks
 *                     before anybody is rostered. The sum of the kitchens that have a figure; null only
 *                     when none has.
 * @param shortOfCrew  a kitchen has a number set and its roster does not reach it. A quiet warning tone
 *                     on the screen and nothing more: it never blocks saving, and it never blocks leave.
 * @param mealKitchenCount how many kitchens are cooking this meal. Not {@code kitchens.size()}: a
 *                     readout can carry one section of a meal that has several — the leave impact
 *                     sends only the section the person away works in — so the list's length answers
 *                     "how many sections are in this readout", never "how many kitchens are on this
 *                     meal". The leave line needs the second question to decide whether naming the
 *                     kitchen tells the approver anything: at a temple whose every lunch is the main
 *                     kitchen's, "Lunch (Main Kitchen) on 24 Sept" says the same word on every line
 *                     and earns none of them.
 * @param kitchens     the same readout per kitchen (Epic 12), in the order the signed-in person sees
 *                     the meal's kitchens ({@code KitchenOrder.forViewer}).
 */
public record MealCrewView(
		UUID mealId,
		LocalDate planDate,
		String mealKind,
		LocalTime readyBy,
		Integer crewRequired,
		int staffIn,
		int volunteers,
		int rostered,
		boolean shortOfCrew,
		int mealKitchenCount,
		List<KitchenCrewView> kitchens) {
}
