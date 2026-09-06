package org.iskcon.kms.meal;

import java.time.LocalDate;
import java.util.List;

/**
 * What reusing a plan would do, worked out without writing anything (2026-09-05).
 *
 * <p><strong>The screen exists for this.</strong> The operation writes across a fortnight of plan in
 * one press, and the old button did it and then reported what it had done — so a planner learned
 * that three meals fell foul of a fast, or that two days were left alone, only afterwards. Everything
 * here is the same arithmetic the commit performs, run first and shown, so the decision is made with
 * the answer in view.
 *
 * @param sourceWasEmpty nothing at all in the source window. The screen says so rather than offering
 *                       an empty list of things to tick.
 * @param kinds    the main meal kinds found in the window, with how many days each appears on.
 * @param events   the events found, by name, with how many times each occurred — which is the honest
 *                 signal for "does this repeat?", since nothing in the schema records it. A name that
 *                 happened once in a fortnight is offered unticked; the planner decides.
 * @param excluded what is deliberately not on offer, each with the reason in words.
 * @param headCounts what each kind is currently planned for, so the screen can show four numbers
 *                 rather than forty-five and let one edit cover the lot.
 * @param days     every target day in order, and what would land on it.
 * @param totals   the same figures the day list adds up to, so the screen does not have to sum them.
 */
public record ReusePlanPreview(
		boolean sourceWasEmpty,
		List<KindFound> kinds,
		List<EventFound> events,
		List<Excluded> excluded,
		List<HeadCount> headCounts,
		List<TargetDay> days,
		Totals totals) {

	public record KindFound(String mealKind, int dayCount, int mealCount) {
	}

	/** @param outside this event's food left the temple, which is the usual mark of a one-off. */
	public record EventFound(String eventName, int occurrences, boolean outside, LocalDate lastSeen) {
	}

	/**
	 * @param reason plain words, printed as written. A festival feast is excluded because its
	 *               occasion is derived from the calendar on the day it is cooked, so copied onto an
	 *               ordinary Wednesday it is a large lunch with the wrong name on it.
	 */
	public record Excluded(String label, String reason, LocalDate on) {
	}

	public record HeadCount(String mealKind, Integer adults, Integer children, Integer seniors) {
	}

	/**
	 * @param alreadyPlanned this day has meals of its own, so the whole day is left alone. Nothing is
	 *                       ever overwritten — the old button's best rule, kept.
	 * @param meals          what would land, each saying whether it would be copied and why not.
	 */
	public record TargetDay(
			LocalDate targetDate,
			LocalDate sourceDate,
			boolean alreadyPlanned,
			String fastName,
			List<PlannedMeal> meals) {
	}

	/**
	 * @param skippedReason null when it would be copied. Otherwise why not, in the words the screen
	 *                      prints: the recipe that carries grain, and the fast that forbids it.
	 */
	public record PlannedMeal(
			String mealKind,
			String eventName,
			String recipeName,
			boolean copied,
			String skippedReason) {
	}

	public record Totals(int meals, int daysWritten, int daysLeftAlone, int notCopied) {
	}
}
