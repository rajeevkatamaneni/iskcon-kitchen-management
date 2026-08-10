package org.iskcon.kms.calendar.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.iskcon.kms.calendar.astro.EarthData;
import org.iskcon.kms.calendar.astro.GcGregorianDate;
import org.springframework.stereotype.Component;

/**
 * Facade over the Gaudiya-Vaishnava rule layer. Mirrors
 * {@code TCalendar.CalculateCalendar(loc, begDate, iCount)} for a location built
 * from latitude/longitude/timezone.
 *
 * <p>Stateless — each call builds its own working state — so it is safe as a singleton bean.
 */
@Component
public final class VaishnavaCalendarEngine {

    public List<CalendarDayResult> computeRange(double latitudeDeg, double longitudeDeg,
            double tzHours, LocalDate start, int days) {
        EarthData earth = new EarthData();
        earth.latitudeDeg = latitudeDeg;
        earth.longitudeDeg = longitudeDeg;
        earth.tzone = tzHours;
        earth.dst = 0;

        GcGregorianDate begDate =
                new GcGregorianDate(start.getYear(), start.getMonthValue(), start.getDayOfMonth(),
                        0.5, tzHours);

        CalendarBuilder builder = new CalendarBuilder();
        CalendarDay[] data = builder.calculate(earth, begDate, days);
        int before = builder.beforeDays();

        List<CalendarDayResult> out = new ArrayList<>(days);
        for (int k = 0; k < days; k++) {
            CalendarDay day = data[before + k];
            DayData a = day.astrodata;

            List<CalendarFestival> festivals = new ArrayList<>();
            for (CalendarDay.DayEvent e : day.dayEvents) {
                if (e.disp >= Cal.CAL_FEST_0 && e.disp <= Cal.CAL_FEST_6) {
                    festivals.add(new CalendarFestival(e.text, e.prio));
                }
            }

            out.add(new CalendarDayResult(
                    LocalDate.of(day.date.year, day.date.month, day.date.day),
                    a.nTithi,
                    a.nTithi >= 15 ? 1 : 0,
                    a.nMasa,
                    a.nGaurabdaYear,
                    a.nNaksatra,
                    a.nYoga,
                    day.nFastType,
                    day.nMhdType,
                    day.ekadasiVrataName,
                    a.sun.sunriseDeg,
                    a.sun.sunsetDeg,
                    festivals));
        }
        return out;
    }
}
