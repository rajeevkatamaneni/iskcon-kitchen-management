package org.iskcon.kms.meal;

import java.time.LocalDate;

/**
 * What a cancel did (D-27, T-307).
 *
 * @param volunteersTold how many volunteers are being told, across every meal cancelled. Zero for
 *                       meals with no shift.
 * @param mealsCancelled how many meals went from having a dish to cook to having none. Zero where
 *                       the meal was already cancelled, which is a quiet no-op as it always was.
 * @param lastDate       for a meal in a series, the date of the last occurrence still standing
 *                       afterwards, or null where none is; null for a meal in no series.
 */
public record CancelledMeals(int volunteersTold, int mealsCancelled, LocalDate lastDate) {
}
