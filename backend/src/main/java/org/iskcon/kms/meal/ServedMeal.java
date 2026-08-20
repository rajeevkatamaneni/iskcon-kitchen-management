package org.iskcon.kms.meal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * One meal — a date and a kind — assembled from the dish rows that share them (B5).
 *
 * <p>There is no meal-line table: one {@code meal_plans} row is one dish, and a lunch of three
 * dishes is three rows carrying the same date, kind, head count and ready-by. That is the right
 * shape for sufficiency and for the order list, but it leaves "a meal" as something a reader has to
 * infer, and the build brief speaks of a meal throughout — one card per meal kind, recording per
 * meal rather than per dish, plates per meal kind. This record is that inference made once, here,
 * so that every screen means the same thing by it.
 *
 * @param serviceId  the {@code meal_services} row, or null when the meal has neither been carded nor
 *                   recorded and so has no row of its own yet.
 * @param plates     what the meal scales to. Never the sum of its dishes: a lunch of three dishes at
 *                   250 servings each is 250 plates, not 750.
 * @param dishes     every dish of this meal, cancelled ones included, in ready-by then name order —
 *                   a cancelled dish is part of the record of what was decided.
 */
public record ServedMeal(
		UUID serviceId,
		LocalDate planDate,
		String mealKind,
		LocalTime readyBy,

		Integer adults,
		Integer children,
		Integer seniors,
		int plates,

		DayType dayType,
		String occasionName,
		String clientName,
		String clientContact,
		String venue,
		String purpose,
		String kitchenNotes,

		String cardNumber,
		Instant cardIssuedAt,

		/** True once the returned job card has been typed in. What is recorded cannot be changed. */
		boolean recorded,
		Instant recordedAt,
		String recordedByName,
		String recordingNote,

		List<MealPlanView> dishes) {

	/** A meal with at least one dish still to be cooked — what the nudge counts and Today says. */
	public boolean awaitingRecord() {
		return !recorded && dishes.stream().anyMatch(d -> d.status() == MealStatus.PLANNED);
	}
}
