package org.iskcon.kms.donation;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;

/**
 * The window the donations ledger is showing, and the window a year earlier it is measured against.
 *
 * <p>All of the arithmetic that makes the year-on-year comparison honest lives here, in one place,
 * because it is the whole value of the feature and it is easy to get quietly wrong. The rule is
 * <b>same point to same point</b>: a window that is 140 days old is compared with the first 140 days
 * of the equivalent window a year earlier, never with the whole of it. Comparing five months of this
 * financial year against twelve months of the last one produces a screen that says giving has
 * collapsed every year until March, which is how these screens mislead.
 *
 * <p>So the prior window is built by taking the number of days already elapsed in the current window
 * and laying that same count out from the prior window's start. It is a day count rather than a
 * calendar rule on purpose: "1 April plus 140 days" means the same thing in a leap year and outside
 * one, whereas "the same date last year" silently gains or loses a day whenever February intervenes.
 *
 * <p>The week is the one period that steps back <b>52 weeks</b> rather than a calendar year. A
 * temple's giving is strongly weekday-shaped — Sunday and the festival days carry it — and a
 * calendar year lands one or two weekdays adrift, so a Monday-to-Sunday week would find itself
 * compared against a Wednesday-to-Tuesday one and the difference would be the calendar, not the
 * donors. Over a month or a year that drift is a rounding error and the calendar date is the more
 * natural thing to say, so those step back a year.
 */
public record LedgerPeriod(
		String period,
		/** The starting calendar year of the financial year on show, where the period names one. */
		Integer financialYear,
		LocalDate from,
		LocalDate to,
		LocalDate previousFrom,
		LocalDate previousTo) {

	public static final String WEEK = "WEEK";
	public static final String MONTH = "MONTH";
	public static final String FINANCIAL_YEAR = "FINANCIAL_YEAR";
	public static final String YEAR = "YEAR";

	/**
	 * The Indian financial year the given date falls in, as its opening day. April to March, which is
	 * the year an 80G receipt is counted against, so it is the only year boundary this product knows.
	 */
	public static LocalDate financialYearStart(LocalDate date) {
		return date.getMonthValue() >= 4
				? LocalDate.of(date.getYear(), 4, 1) : LocalDate.of(date.getYear() - 1, 4, 1);
	}

	/**
	 * Turns the period the screen asked for into the two windows the totals are drawn from.
	 *
	 * @param period        one of WEEK, MONTH, FINANCIAL_YEAR, YEAR; null reads as MONTH
	 * @param financialYear the opening calendar year, required by YEAR and ignored by the rest
	 * @param today         the temple's own today, not the server's
	 */
	static LedgerPeriod resolve(String period, Integer financialYear, LocalDate today) {
		String kind = period == null || period.isBlank() ? MONTH : period;
		LocalDate start;
		LocalDate end;
		Integer year = null;

		switch (kind) {
			case WEEK -> {
				// Monday-first, which is what a temple's own week already is: the roster, the meal
				// planner and the shift grid all open on a Monday, and a total that started on a
				// Sunday would disagree with every other screen about which week this is.
				start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
				end = today;
			}
			case MONTH -> {
				start = today.withDayOfMonth(1);
				end = today;
			}
			case FINANCIAL_YEAR -> {
				start = financialYearStart(today);
				end = today;
				year = start.getYear();
			}
			case YEAR -> {
				if (financialYear == null) {
					throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("period", kind));
				}
				start = LocalDate.of(financialYear, 4, 1);
				if (start.isAfter(today)) {
					// A financial year that has not begun has no figure to show and no honest
					// comparison to make, so it is refused rather than rendered as a row of zeroes.
					throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
							Map.of("financialYear", financialYear));
				}
				LocalDate lastDay = start.plusYears(1).minusDays(1);
				// A closed year runs to its own 31 March; the year we are living in runs to today,
				// so that a part-year is never dressed up as a whole one.
				end = lastDay.isAfter(today) ? today : lastDay;
				year = financialYear;
			}
			default -> throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("period", kind));
		}

		long elapsedDays = ChronoUnit.DAYS.between(start, end);
		LocalDate priorStart = WEEK.equals(kind) ? start.minusWeeks(52) : start.minusYears(1);
		return new LedgerPeriod(kind, year, start, end, priorStart, priorStart.plusDays(elapsedDays));
	}
}
