package org.iskcon.kms.calendar.engine;

import java.time.LocalDate;
import java.util.List;

/** One day of the computed Gaudiya-Vaishnava calendar. */
public record CalendarDayResult(
        LocalDate date,
        int tithi,
        int paksa,
        int masa,
        int gaurabdaYear,
        int naksatra,
        int yoga,
        int fastCode,         // 0 none, else FastType raw (0x201..0x207)
        int mahadvadashiCode, // ekadashiType raw (0x100..0x110)
        String ekadashiName,  // "" when none
        double sunriseDeg,
        double sunsetDeg,     // sun.rise_deg / sun.set_deg
        List<CalendarFestival> festivals) {
}
