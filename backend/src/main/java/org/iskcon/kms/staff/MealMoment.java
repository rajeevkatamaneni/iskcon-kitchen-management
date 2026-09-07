package org.iskcon.kms.staff;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Locale;

/**
 * One meal, reduced to what the head count needs to know about it: the day it is cooked on, the time
 * its food must be ready (item 19), and — since D-14 — which meal it actually <em>is</em>, so that a
 * shift posted for it can say so rather than be guessed at by the clock.
 *
 * <p>It lives here rather than in the planner because it is the question the roster is asked, not
 * the answer the planner keeps. {@link WorkforceService} takes a batch of these and resolves the
 * week once for all of them; passing whole meal records across the boundary would hand the roster a
 * recipe, a client and a head count it has no business reading.
 *
 * <h2>Why the kind and the event name are folded here, in the constructor</h2>
 *
 * <p>Because there is no meal table to join on. A meal is an inference the planner assembles by
 * grouping dish rows on {@code (plan_date, meal_kind, event_name)}, and its own key normalises that
 * name — null or blank becomes {@code ""}, anything else is trimmed and folded to lower case — so
 * that "Bhajan Prasadam" and "bhajan prasadam" are one event rather than two. A shift's link has to
 * be normalised by <em>identically</em> the same rule or it matches nothing, and the failure is
 * invisible: the count reads zero, which looks exactly like a shift nobody signed up for. Nobody
 * would go looking.
 *
 * <p>That rule cannot be imported — the planner's key is private to it — so it is replicated, and
 * replicated in <strong>one</strong> place: this canonical constructor. Every {@code MealMoment} in
 * the product is folded on the way in, and {@link #isFor} folds the link it is handed on the way in
 * too, so there is no call site at which somebody can forget. Two copies of a rule are a risk; two
 * copies in two classes each with four call sites is a certainty.
 *
 * <p>The kind is folded as well, which the planner's key does not do. That is deliberate and it is
 * strictly safe: folding both sides matches everything an exact comparison would match and nothing
 * it would not, and it removes the one remaining way a caller can post {@code "lunch"} against a
 * temple that stores {@code "Lunch"} and be quietly counted nowhere. Nothing displays these two
 * components — the readout takes its labels from the meal record itself — so folding costs no
 * legibility anywhere it is read.
 *
 * @param date     the day the meal is cooked on.
 * @param readyBy  the time the food must be ready, which is the moment the roster is asked about.
 *                 Null asks about the whole day.
 * @param mealKind the meal's kind, folded. Never null: an absent kind is {@code ""}.
 * @param eventName the event's own name where the meal is one, folded. Never null; a meal that is
 *                 not an event is {@code ""}, so a map lookup can never quietly miss on a null.
 */
public record MealMoment(LocalDate date, LocalTime readyBy, String mealKind, String eventName) {

	public MealMoment {
		mealKind = fold(mealKind);
		eventName = fold(eventName);
	}

	/**
	 * Whether a shift linked to this date, kind and event name was posted for <em>this</em> meal.
	 *
	 * <p>The link is handed over raw, as the temple typed it and as the column stores it, and folded
	 * here — the caller is never trusted to have folded it, because that is the trap this whole class
	 * exists to close.
	 *
	 * <p>The readiness time is deliberately not part of it. A linked shift counts toward its meal
	 * whatever the clock says: that is the entire point of the link, and asking the shift to also
	 * span the ready-by would reinstate the rule it was built to replace.
	 */
	public boolean isFor(LocalDate linkedDate, String linkedKind, String linkedEventName) {
		return date.equals(linkedDate)
				&& mealKind.equals(fold(linkedKind))
				&& eventName.equals(fold(linkedEventName));
	}

	/**
	 * Null and blank are the same thing and both become {@code ""}; everything else is trimmed and
	 * folded to lower case. The same rule the planner's meal key applies, and the reason it is
	 * written once.
	 */
	private static String fold(String value) {
		return value == null || value.isBlank() ? "" : value.trim().toLowerCase(Locale.ROOT);
	}
}
