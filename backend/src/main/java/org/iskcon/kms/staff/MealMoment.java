package org.iskcon.kms.staff;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * One meal, reduced to what the head count needs to know about it: which meal it is, the day it is
 * cooked on, and the time its food must be ready (item 19).
 *
 * <p>It lives here rather than in the planner because it is the question the roster is asked, not
 * the answer the planner keeps. {@link WorkforceService} takes a batch of these and resolves the
 * week once for all of them; passing whole meal records across the boundary would hand the roster a
 * recipe, a client and a head count it has no business reading.
 *
 * <h2>Matched by id since D-27</h2>
 *
 * <p>Until D-27 there was no meal row to point at. A meal was a date, a kind and an event name
 * inferred from dish rows, a shift copied those three as text, and this record carried a folding rule
 * — trim, lower-case, blank is nothing — replicated from the planner's private key so that
 * "Janmashtami Feast" and "  janmashtami feast " would match. Get the fold wrong by one character and
 * a shift counted toward nothing while looking like a shift nobody signed up for. Rajeev ruled that
 * whole arrangement out: <em>"identifying things by text is a terrible idea and one that WILL fail
 * eventually."</em> A meal now has its own id, a shift points at it, and {@link #isFor} compares
 * ids. The fold is gone because there is nothing left to fold.
 *
 * <p>{@code mealKind} and {@code eventName} stay for the reader's benefit only — nothing matches on
 * them. Equality of two moments is equality of all five components; since the id alone identifies a
 * meal, two moments for the same meal built from the same row are equal, and two different meals due
 * at the same minute are two moments, which is what a shift for one of them needs.
 *
 * @param mealId    the meal's own id.
 * @param date      the day the meal is cooked on.
 * @param readyBy   the time the food must be ready, which is the moment the roster is asked about.
 *                  Null asks about the whole day.
 * @param mealKind  the kind's name as the temple calls it, for display.
 * @param eventName the event's name where the meal is one, else null; for display.
 */
public record MealMoment(UUID mealId, LocalDate date, LocalTime readyBy, String mealKind, String eventName) {

	/**
	 * Whether a shift for {@code linkedMealId} is a shift for <em>this</em> meal.
	 *
	 * <p>The readiness time is deliberately not part of it. A shift for a meal counts toward that meal
	 * whatever the clock says: that is the entire point of the link, and asking the shift to also span
	 * the ready-by would reinstate the rule it was built to replace. A null link is a shift not for a
	 * meal and is never for this one.
	 */
	public boolean isFor(UUID linkedMealId) {
		return mealId != null && mealId.equals(linkedMealId);
	}
}
