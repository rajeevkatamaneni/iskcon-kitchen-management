package org.iskcon.kms.meal;

import java.util.List;
import java.util.UUID;

/**
 * One kitchen's hands for one meal (Epic 12): its People needed against its own staff.
 *
 * <p>Rajeev, 2026-09-19: <em>"'People needed' is answered per kitchen; the rostered staff shown are
 * that kitchen's staff."</em> A meal two kitchens cook is two crews. A pastry cook rostered in the
 * Sweets kitchen is not a pair of hands on the Main kitchen's dal, so adding the two kitchens' staff
 * into one figure and holding it against one target would call a lunch covered while the Main kitchen
 * stood four short.
 *
 * <p><strong>Rostered staff</strong> are the people whose staff record is in this kitchen and whose
 * working window covers the meal's ready-by, which is the rule every meal already used, sliced by
 * kitchen and nothing else.
 *
 * <p><strong>Volunteers</strong> belong to no kitchen: a shift is posted for a meal or for a stretch
 * of the day. So the meal's volunteers are counted in one section only: the main kitchen's where it is
 * cooking this meal, otherwise the first section in the order this person sees them. Every other
 * section reads 0. That keeps the kitchens adding up to the meal and counts no volunteer twice. It is
 * an assumption flagged to Rajeev (docs/work/DISPATCH.md, Epic 12), not a ruling of his.
 *
 * @param crewRequired this kitchen's People needed ({@code meal_kitchens.crew_required}), or null where
 *                     nobody has said. Null is not zero and is never a shortfall.
 * @param staffNames   the rostered staff by name, in the roster's order; {@code staffIn} is its size
 * @param rostered     staffIn + volunteers, the figure {@code crewRequired} is measured against
 * @param shortOfCrew  a number was set and {@code rostered} does not reach it. Told, never enforced.
 */
public record KitchenCrewView(
		UUID kitchenId,
		String kitchenName,
		Integer crewRequired,
		int staffIn,
		List<String> staffNames,
		int volunteers,
		int rostered,
		boolean shortOfCrew) {
}
