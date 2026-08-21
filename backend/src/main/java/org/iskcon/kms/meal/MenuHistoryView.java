package org.iskcon.kms.meal;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What was cooked for this festival last time (item 26b) — <em>"Last Janmashtami, 26 August 2025 —
 * 18 preparations."</em>
 *
 * <p>Twenty preparations is an hour of scrolling a flat grid of every recipe the temple has and
 * ticking twenty boxes, with nothing on the screen saying which of them belong together. E4-S2
 * promised that planning "Janmashtami" would carry menu history; this is the read that keeps the
 * promise, and it reads data that has been there all along.
 *
 * <p>Matched on the occasion's <em>name</em> and not on an occasion id, which is the choice V48 made
 * for the meal kind and for the same reason: {@code meal_plans.occasion_name} is denormalized on
 * purpose, "so removing an occasion never orphans the plan" (V22). A temple may delete an occasion,
 * and the feasts cooked under it must keep reading as what they were.
 *
 * <p>What carries is the list of preparations, and only that. Servings do not: they follow this
 * year's head count. Last year's per-dish overrides do not either — an override was a judgement
 * about last year's crowd, and re-applying it against a different head count would be wrong in a way
 * nobody would notice. Nothing here is applied automatically; the menu is offered, one press puts it
 * in, and everything stays editable afterwards.
 *
 * @param lastCookedOn  when this occasion was last cooked for, or null where it never has been. The
 *                      first ever Janmashtami has nothing to offer and the control is absent — which
 *                      is why null is the answer rather than an empty list that looks like a bug.
 * @param preparationCount how many preparations that meal had in all, including the ones no longer
 *                      available. It is the figure the sentence quotes: <em>2 of last year's 18</em>.
 * @param missingCount  how many of them are no longer in the temple's recipes. Said out loud rather
 *                      than silently dropped: a menu that comes back two preparations shorter without
 *                      saying so is a menu somebody serves two preparations short.
 * @param preparations  the ones that can still be planned, in the order they read on the card.
 */
public record MenuHistoryView(
		String occasionName,
		LocalDate lastCookedOn,
		String mealKind,
		int preparationCount,
		int missingCount,
		List<Preparation> preparations) {

	/** One preparation of last year's menu, as the composer needs to tick it. */
	public record Preparation(UUID recipeId, String recipeName) {
	}

	/** Nothing to offer: this occasion has never been cooked for before. */
	static MenuHistoryView none(String occasionName) {
		return new MenuHistoryView(occasionName, null, null, 0, 0, List.of());
	}
}
