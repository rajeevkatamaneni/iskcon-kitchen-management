package org.iskcon.kms.calendar.engine;

import org.iskcon.kms.calendar.astro.EarthData;
import org.iskcon.kms.calendar.astro.GcAyanamsha;
import org.iskcon.kms.calendar.astro.GcGregorianDate;
import org.iskcon.kms.calendar.astro.GcMath;
import org.iskcon.kms.calendar.astro.MoonData;

/** Faithful port of GCNaksatra.py. */
final class GcNaksatra {

    private GcNaksatra() {
    }

    static double calculateMidnightNaksatra(GcGregorianDate date, EarthData earth) {
        MoonData moon = new MoonData();
        GcGregorianDate d = new GcGregorianDate(date);
        d.shour = 1.0;
        double jdate = d.getJulianDetailed();
        moon.calculate(jdate, earth);
        double deg = GcMath.putIn360(moon.longitudeDeg - GcAyanamsha.getAyanamsa(jdate));
        return Math.floor((deg * 3.0) / 40.0);
    }

    static int getNextNaksatra(EarthData ed, GcGregorianDate startDate, GcGregorianDate nextDate,
            boolean forward) {
        double phi = 40.0 / 3.0;
        double jday = startDate.getJulianComplete();
        MoonData moon = new MoonData();
        GcGregorianDate d = new GcGregorianDate(startDate);
        double ayanamsa = GcAyanamsha.getAyanamsa(jday);
        int prevNaks;
        int newNaks = -1;
        double dir = forward ? 1.0 : -1.0;
        double scanStep = 0.5 * dir;

        GcGregorianDate xd = new GcGregorianDate();

        moon.calculate(jday, ed);
        double l1 = GcMath.putIn360(moon.longitudeDeg - ayanamsa);
        prevNaks = (int) Math.floor(l1 / phi);

        int counter = 0;
        while (counter < 20) {
            double xj = jday;
            xd.set(d);

            jday += scanStep;
            d.shour += scanStep;
            d.normalizeHours();

            moon.calculate(jday, ed);
            double l2 = GcMath.putIn360(moon.longitudeDeg - ayanamsa);
            newNaks = (int) Math.floor(l2 / phi);
            if (prevNaks != newNaks) {
                jday = xj;
                d.set(xd);
                scanStep *= 0.5;
                counter += 1;
            } else {
                l1 = l2;
            }
        }
        nextDate.set(d);
        return newNaks;
    }

    static int getNextNaksatra(EarthData ed, GcGregorianDate startDate, GcGregorianDate nextDate) {
        return getNextNaksatra(ed, startDate, nextDate, true);
    }

    static int getPrevNaksatra(EarthData ed, GcGregorianDate startDate, GcGregorianDate nextDate) {
        return getNextNaksatra(ed, startDate, nextDate, false);
    }

    static double getEndHour(EarthData earth, GcGregorianDate yesterday, GcGregorianDate today) {
        GcGregorianDate nend = new GcGregorianDate();
        GcGregorianDate snd = new GcGregorianDate(yesterday);
        snd.shour = 0.5;
        getNextNaksatra(earth, snd, nend);
        return nend.getJulian() - today.getJulian() + nend.shour;
    }
}
