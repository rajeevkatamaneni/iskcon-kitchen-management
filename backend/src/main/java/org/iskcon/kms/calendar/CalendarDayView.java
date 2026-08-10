package org.iskcon.kms.calendar;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * One day of the Vaishnava calendar as the planner and API read it (E4-S1). Astronomical facts are
 * codes ({@code tithi} 0..29, {@code masa} 0..12, {@code paksa} 0/1) the frontend maps to names;
 * the Ekadashi fast, its name and any Maha-Dvadashi variant, and the day's festivals are resolved
 * here.
 */
public record CalendarDayView(
		LocalDate date,
		int tithi,
		int paksa,
		int masa,
		Integer gaurabdaYear,
		Integer naksatra,
		boolean isEkadashi,
		String ekadashiName,
		String mahadvadashi,
		String fastType,
		LocalTime sunrise,
		LocalTime sunset,
		List<CalendarFestivalView> festivals,
		boolean overridden,
		String overrideReason) {

	/** A named festival on a day, with its GCAL display priority (lower = more prominent). */
	public record CalendarFestivalView(String text, int priority) {
	}
}
