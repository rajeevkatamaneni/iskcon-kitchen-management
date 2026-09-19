package org.iskcon.kms.meal;

import java.time.LocalDate;
import java.util.List;

/**
 * What repeating an event did — or, from the preview, what it would do (T-307). The two are the same
 * record from the same walk, so the preview cannot promise dates the repeat then does not make.
 *
 * <p><strong>A series now, where it used to be copies.</strong> E4-S15 D8 made the copies
 * deliberately unrelated, so that no screen ever had to ask "this one or all of them?". Rajeev asked
 * for exactly that question on 2026-09-19 — <em>"a cancel of this repeating event should ask JUST
 * this event OR all events from this point onwards"</em> — so the source and every copy now share a
 * series ({@code meal_series}, V149). Each copy is still an ordinary meal: editable and cancellable
 * on its own, read by everything else exactly like any other meal.
 *
 * @param copies                how many meals were made (or would be).
 * @param preparations          how many dishes were written. Six copies of a two-dish event is twelve.
 * @param dates                 the date of every copy, in order.
 * @param skippedFasting        dates skipped because a dish does not suit the Ekadashi falling there.
 *                              Refused rather than acknowledged on the planner's behalf — nobody is
 *                              looking at that meal to say it is all right.
 * @param skippedAlreadyPlanned dates where this same event (same day, kind and name) is already
 *                              planned, cooked or recorded. Left alone: planning it again would add
 *                              a second set of the same dishes to it.
 * @param lastDate              the last copy's date, or null where none was made.
 * @param series                the series the source belongs to afterwards; from the preview, what it
 *                              would be ({@code seriesId} null for a series not yet made). Null only
 *                              where nothing was made and the source was in no series.
 */
public record RepeatEventResult(
		int copies,
		int preparations,
		List<LocalDate> dates,
		List<LocalDate> skippedFasting,
		List<LocalDate> skippedAlreadyPlanned,
		LocalDate lastDate,
		MealSeriesView series) {
}
