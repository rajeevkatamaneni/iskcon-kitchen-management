package org.iskcon.kms.meal;

/**
 * The planner's suggestion for a date (E4-S4): the day-type it would auto-tag, the festival occasion
 * if any, and the servings to pre-fill from that occasion's default. All overridable by the planner.
 */
public record DayContext(
		DayType suggestedDayType,
		String occasionName,
		Integer suggestedServings,
		boolean isEkadashi) {
}
