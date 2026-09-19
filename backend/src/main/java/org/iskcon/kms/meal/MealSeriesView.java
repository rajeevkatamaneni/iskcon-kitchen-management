package org.iskcon.kms.meal;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The repeating event a meal is one occurrence of (T-307), as the planner shows it: <em>"Repeats
 * every 2 weeks until 31 Dec 2026 · 3 of 8"</em>.
 *
 * <p>Read in the same statement as the meal itself, never one query per meal: the week view reads
 * dozens of meals at once and the statement count is watched ({@code ShoppingListStatementCountIT}).
 *
 * @param seriesId   the series' own id.
 * @param everyWeeks how many weeks apart its occurrences were made, 1 to 12. The latest repeat's
 *                   answer where a series was extended with a different gap.
 * @param until      the last date it was repeated up to. Cancelling "this and every later one"
 *                   brings it back to the last occurrence still standing.
 * @param position   which occurrence this is, 1-based, counting only the ones not cancelled, in date
 *                   order. Null for a cancelled occurrence, which is not one of the {@code count}.
 * @param count      how many occurrences are not cancelled — past, cooked and recorded ones included,
 *                   because they happened.
 */
public record MealSeriesView(UUID seriesId, int everyWeeks, LocalDate until, Integer position, int count) {
}
