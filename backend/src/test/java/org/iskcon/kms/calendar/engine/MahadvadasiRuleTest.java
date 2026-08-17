package org.iskcon.kms.calendar.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rule that overrides the sunrise rule: <em>on a Maha-Dvadashi the Ekadashi is not fasted at all
 * — the fast moves to the Dvadashi that follows it.</em> {@link EkadashiSunriseRuleTest} names the
 * ordinary case; this names the eight exceptions to it.
 *
 * <p>It exists because we shipped without it. The port carried a defect from its Python reference:
 * {@code IsMhd58} is a boolean predicate in the original C++ GCAL, and the Python collapsed it to
 * returning the type with {@code EV_NULL} (0x100) for "none", but kept the C++ call site's
 * {@code != 0} test — and 0x100 is not 0. So on every Gaura-paksa Dvadashi the engine took the
 * "Maha-Dvadashi 5–8" branch with a type of <em>none</em>, which pre-empted the branch that finds
 * Vyanjuli, Paksavardhini and Suddha. Gaura-paksa Vyanjuli and Paksavardhini were therefore lost
 * entirely and the temple fasted a day early: Pavitropana 2026 on 23 August rather than 24 August,
 * Pandava Nirjala 2025 on 6 June rather than 7 June.
 *
 * <p>Both cases below are Vyanjuli — the Ekadashi is unbroken from arunodaya through sunrise, and
 * the Dvadashi that follows it spans two sunrises. That vriddhi Dvadashi is what moves the fast.
 */
class MahadvadasiRuleTest {

	// Bengaluru, the location the whole calendar gate is validated against.
	private static final double LAT = 12.9716;
	private static final double LON = 77.5946;
	private static final double TZ = 5.5;

	private static final int EKADASI_GAURA = 25;
	private static final int DVADASI_GAURA = 26;

	/**
	 * Pavitropana Ekadashi 2026. The Ekadashi tithi runs from 02:00 on 23 August to 04:18 on the
	 * 24th, so the 23rd is the sunrise that falls inside it and the plain rule would fast then. But
	 * the Dvadashi that follows lasts until 06:23 on the 25th — past that morning's 06:09 sunrise —
	 * so it is a vriddhi Dvadashi, the Ekadashi is Vyanjuli-viddha, and the fast is kept on the 24th.
	 * The 25th is then the parana day, with a window of a few minutes between sunrise and the tithi
	 * ending.
	 */
	@Test
	@DisplayName("Vyanjuli moves the fast off the Ekadashi and on to the Dvadashi (Pavitropana 2026)")
	void vanjuliMovesTheFastToTheDvadasi() {
		Map<LocalDate, CalendarDayResult> days = computeFrom(LocalDate.of(2026, 8, 18), 12);

		CalendarDayResult ekadasi = days.get(LocalDate.of(2026, 8, 23));
		CalendarDayResult dvadasi = days.get(LocalDate.of(2026, 8, 24));
		CalendarDayResult paranaDay = days.get(LocalDate.of(2026, 8, 25));

		// The 23rd is Ekadashi at sunrise and would be the fast under the sunrise rule alone.
		assertThat(ekadasi.tithi()).isEqualTo(EKADASI_GAURA);
		assertThat(ekadasi.fastCode()).isZero();
		assertThat(ekadasi.ekadashiName()).isEmpty();

		// It is not: the 24th carries the fast, named for the Ekadashi it stands in for.
		assertThat(dvadasi.tithi()).isEqualTo(DVADASI_GAURA);
		assertThat(dvadasi.fastCode()).isEqualTo(Cal.FAST_EKADASI);
		assertThat(dvadasi.ekadashiName()).isEqualTo("Pavitraropana Ekadasi");
		assertThat(dvadasi.mahadvadashiCode()).isEqualTo(Cal.EV_VYANJULI);

		// The Dvadashi spans two sunrises — the vriddhi that makes this a Maha-Dvadashi at all.
		assertThat(paranaDay.tithi()).isEqualTo(DVADASI_GAURA);
		assertThat(paranaDay.fastCode()).isZero();
	}

	/**
	 * Pandava Nirjala 2025, the same rule a year earlier — and the reason this is worth two cases.
	 * Our own correctness note recorded 6 June as a "GCAL-vs-drik siddhanta divergence" and accepted
	 * it. It was not a divergence of method; it was this defect. With Vyanjuli restored the engine
	 * says 7 June, which is what the published calendars say.
	 */
	@Test
	@DisplayName("Pandava Nirjala 2025 is kept on 7 June, not 6 June")
	void nirjalaIsKeptOnTheDvadasi() {
		Map<LocalDate, CalendarDayResult> days = computeFrom(LocalDate.of(2025, 6, 1), 12);

		assertThat(days.get(LocalDate.of(2025, 6, 6)).fastCode()).isZero();

		CalendarDayResult seventh = days.get(LocalDate.of(2025, 6, 7));
		assertThat(seventh.fastCode()).isEqualTo(Cal.FAST_EKADASI);
		assertThat(seventh.ekadashiName()).isEqualTo("Pandava Nirjala Ekadasi");
		assertThat(seventh.mahadvadashiCode()).isEqualTo(Cal.EV_VYANJULI);
	}

	/**
	 * The defect's quieter symptom, stated as an invariant over three years. Because the bad branch
	 * still ran its "this is the fasting day" block before being undone, it left days named for an
	 * Ekadashi they did not fast, and days that fasted without any classification at all — 31 December
	 * 2025 fasted with a Maha-Dvadashi type of <em>none</em>, so nothing on screen said why the fast
	 * had moved. Fast, name and classification are one fact and must agree.
	 */
	@Test
	@DisplayName("the fast, its name and its classification always agree")
	void fastNameAndClassificationAgree() {
		List<CalendarDayResult> days = new VaishnavaCalendarEngine()
				.computeRange(LAT, LON, TZ, LocalDate.of(2024, 1, 1), 1096);

		List<CalendarDayResult> fasts =
				days.stream().filter(d -> d.fastCode() == Cal.FAST_EKADASI).toList();
		assertThat(fasts).hasSizeGreaterThan(60); // ~24 a year, so the filter really caught them

		assertThat(days).allSatisfy(day -> {
			boolean fasting = day.fastCode() == Cal.FAST_EKADASI;

			assertThat(day.ekadashiName().isEmpty())
					.as("%s: named '%s' but fasting=%s", day.date(), day.ekadashiName(), fasting)
					.isEqualTo(!fasting);

			assertThat(day.mahadvadashiCode() == Cal.EV_NULL)
					.as("%s: classification %s but fasting=%s", day.date(),
							Integer.toHexString(day.mahadvadashiCode()), fasting)
					.isEqualTo(!fasting);
		});
	}

	private static Map<LocalDate, CalendarDayResult> computeFrom(LocalDate from, int days) {
		return new VaishnavaCalendarEngine().computeRange(LAT, LON, TZ, from, days).stream()
				.collect(Collectors.toMap(CalendarDayResult::date, d -> d));
	}
}
