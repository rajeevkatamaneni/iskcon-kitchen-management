package org.iskcon.kms.calendar.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rule the temple states in its own words: <em>if the Ekadashi tithi begins after sunrise, the
 * fast is kept the next day.</em>
 *
 * <p>The engine already obeys it, and obeys it by construction rather than by a rule written down
 * anywhere — {@code DayData.dayCalc} places the sun and moon at that day's local sunrise, so the
 * tithi it records is the one prevailing then, and a tithi that begins at nine in the morning simply
 * is not that day's. {@link CalendarReferenceTest} would catch a regression, but it would report it
 * as a mismatch on day 79 of 400, which tells whoever is reading it nothing about what broke.
 *
 * <p>So this names the rule. If someone ever changes the moment the tithi is sampled, this fails
 * saying which rule stopped being true, and a temple is spared fasting on the wrong day.
 */
class EkadashiSunriseRuleTest {

	// Bengaluru, the location the whole calendar gate is validated against.
	private static final double LAT = 12.9716;
	private static final double LON = 77.5946;
	private static final double TZ = 5.5;

	// Tithis run 0..29 across both fortnights: 0..14 waxing, 15..29 waning. So each name has two
	// indices, and 9 March 2025 falls in the waning fortnight.
	private static final int DASAMI_WANING = 24;
	private static final int EKADASI_WANING = 25;
	private static final List<Integer> DASAMI = List.of(9, 24);
	private static final List<Integer> EKADASI_OR_DVADASI = List.of(10, 25, 11, 26);

	/**
	 * 9 March 2025 is the sharpest case in the reference year. Sunrise is 06:30, and Dasami is 94.6%
	 * elapsed by then — so Ekadashi begins roughly an hour and twenty minutes later, in the middle of
	 * that morning. A calendar that asked "is it Ekadashi at any point today?" would fast on the 9th.
	 * A calendar that asks what the tithi is at sunrise fasts on the 10th, which is what ISKCON does.
	 */
	@Test
	@DisplayName("an Ekadashi tithi that begins after sunrise is kept the following day")
	void aTithiBeginningAfterSunriseBelongsToTheNextDay() {
		Map<LocalDate, CalendarDayResult> days = computeAround(LocalDate.of(2025, 3, 7), 8);

		CalendarDayResult ninth = days.get(LocalDate.of(2025, 3, 9));
		CalendarDayResult tenth = days.get(LocalDate.of(2025, 3, 10));

		// At sunrise on the 9th it is still Dasami — Ekadashi has not begun.
		assertThat(ninth.tithi()).isEqualTo(DASAMI_WANING);
		assertThat(ninth.fastCode()).isZero();
		assertThat(ninth.ekadashiName()).isEmpty();

		// The 10th is the first sunrise that falls inside Ekadashi, so the fast is kept then.
		assertThat(tenth.tithi()).isEqualTo(EKADASI_WANING);
		assertThat(tenth.fastCode()).isNotZero();
		assertThat(tenth.ekadashiName()).isEqualTo("Amalaki vrata Ekadasi");
	}

	/**
	 * The rule stated as the thing it forbids, across a whole year.
	 *
	 * <p>Deliberately not "every fast has Ekadashi at its own sunrise" — that is false, and usefully
	 * so. A fast may legitimately sit on Dvadashi: in 2025 four of them do, where the Maha-Dvadashi
	 * rules move it forward. What can never happen is a fast on a morning that is still Dasami,
	 * because that is precisely the day whose sunrise came before the Ekadashi tithi began.
	 */
	@Test
	@DisplayName("a fast is never kept on a morning that is still Dasami")
	void noFastIsEverKeptBeforeTheTithiHasBegun() {
		List<CalendarDayResult> year = new VaishnavaCalendarEngine()
				.computeRange(LAT, LON, TZ, LocalDate.of(2025, 1, 1), 365);

		List<CalendarDayResult> fasts = year.stream()
				.filter(d -> !d.ekadashiName().isEmpty() && d.fastCode() != 0)
				.toList();

		assertThat(fasts).hasSizeGreaterThan(20); // roughly two a month, so the filter really caught them
		assertThat(fasts).allSatisfy(day -> {
			assertThat(DASAMI)
					.as("fast on %s falls on a morning still in Dasami", day.date())
					.doesNotContain(day.tithi());
			assertThat(EKADASI_OR_DVADASI)
					.as("fast on %s sits on tithi %d, neither Ekadashi nor Dvadashi", day.date(), day.tithi())
					.contains(day.tithi());
		});
	}

	private static Map<LocalDate, CalendarDayResult> computeAround(LocalDate from, int days) {
		return new VaishnavaCalendarEngine().computeRange(LAT, LON, TZ, from, days).stream()
				.collect(Collectors.toMap(CalendarDayResult::date, d -> d));
	}
}
