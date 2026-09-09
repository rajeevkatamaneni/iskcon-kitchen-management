package org.iskcon.kms.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One saved meal plan's claim on a single ingredient (T-086).
 *
 * <p>This is the <em>list</em> half of committed stock, and it answers a different question from the
 * total on the inventory table. The total says <em>how much of this is already spoken for</em>, which
 * is what a storekeeper scans a column for; this says <em>which meals spoke for it</em>, which is what
 * somebody asks the moment the total surprises them. Both exist deliberately.
 *
 * @param mealPlanId the plan row this claim comes from. Not rendered — it is the React key, and the
 *                   only stable identity a dish has when a day holds two of the same recipe.
 * @param planDate   the day the meal is planned for, and the day the detail page links to. The
 *                   planner addresses a day as {@code /planner/<ISO date>}, so this is the whole of
 *                   the link.
 * @param mealKind   Breakfast, Lunch, Dinner, or a temple's own kind. What the day calls this slot.
 * @param eventName  what an event is called (E4-S15), or null on an ordinary meal. Where it is set
 *                   it is the better label: a reader recognises "Saturday reading" and does not
 *                   recognise "Event".
 * @param recipeName the dish. Two dishes in one meal each claim separately, because they each draw
 *                   separately and a cook who removes one wants to see that one's number go.
 * @param quantity   how much of the ingredient this dish's scaled recipe intends to draw, in the
 *                   ingredient's canonical unit — the same unit the on-hand figure is in, so the
 *                   list adds up to the total by eye.
 * @param unit       the stored unit name, as every {@code unit} field in this API carries. Display
 *                   happens where a person reads it.
 */
public record CommittedMeal(
		UUID mealPlanId,
		LocalDate planDate,
		String mealKind,
		String eventName,
		String recipeName,
		BigDecimal quantity,
		String unit) {
}
