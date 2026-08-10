package org.iskcon.kms.calendar.engine;

import org.iskcon.kms.calendar.astro.EarthData;
import org.iskcon.kms.calendar.astro.GcGregorianDate;
import org.iskcon.kms.calendar.astro.GcMath;
import org.iskcon.kms.calendar.astro.MoonData;
import org.iskcon.kms.calendar.astro.SunData;

/** Faithful port of GCConjunction.py. */
final class GcConjunction {

    private GcConjunction() {
    }

    static boolean isConjunction(double m1, double s1, double s2, double m2) {
        if (m2 < m1) {
            m2 += 360.0;
        }
        if (s2 < s1) {
            s2 += 360.0;
        }
        if ((m1 <= s1) && (s1 < s2) && (s2 <= m2)) {
            return true;
        }

        m1 = GcMath.putIn180(m1);
        m2 = GcMath.putIn180(m2);
        s1 = GcMath.putIn180(s1);
        s2 = GcMath.putIn180(s2);

        return (m1 <= s1) && (s1 < s2) && (s2 <= m2);
    }

    static double getPrevConjunction(GcGregorianDate date, EarthData earth, boolean forward) {
        double dir = forward ? 1.0 : -1.0;
        MoonData moon = new MoonData();

        GcGregorianDate d = new GcGregorianDate(date);
        d.shour = 0.5;
        d.tzone = 0.0;
        double jd = d.getJulian();

        moon.calculate(jd, earth);
        double prevSun = SunData.getSunLongitude(d);
        double prevMoon = moon.longitudeDeg;
        double prevDiff = GcMath.putIn180(prevSun - prevMoon);

        for (int bCont = 0; bCont < 32; bCont++) {
            if (forward) {
                d.nextDay();
            } else {
                d.previousDay();
            }
            jd += dir;
            moon.calculate(jd, earth);
            double nowSun = SunData.getSunLongitude(d);
            double nowMoon = moon.longitudeDeg;
            double nowDiff = GcMath.putIn180(nowSun - nowMoon);

            if (isConjunction(nowMoon, nowSun, prevSun, prevMoon)) {
                if (prevDiff == nowDiff) {
                    return 0;
                }
                double x = Math.abs(nowDiff) / Math.abs(prevDiff - nowDiff);
                if (x < 0.5) {
                    if (forward) {
                        d.previousDay();
                    }
                    d.shour = x + 0.5;
                } else {
                    if (!forward) {
                        d.nextDay();
                    }
                    d.shour = x - 0.5;
                }
                date.set(d);
                prevSun = GcMath.putIn360(prevSun);
                nowSun = GcMath.putIn360(nowSun);
                if (Math.abs(prevSun - nowSun) > 10.0) {
                    return GcMath.putIn180(nowSun)
                            + (GcMath.putIn180(prevSun) - GcMath.putIn180(nowSun)) * x;
                } else {
                    return nowSun + (prevSun - nowSun) * x;
                }
            }
            prevSun = nowSun;
            prevMoon = nowMoon;
            prevDiff = nowDiff;
        }

        return 1000.0;
    }

    static double getNextConjunction(GcGregorianDate date, EarthData earth) {
        return getPrevConjunction(date, earth, true);
    }

    static double getPrevConjunctionEx(GcGregorianDate testDate, GcGregorianDate found,
            boolean thisConj, EarthData earth, boolean forward) {
        double phi = 12.0;
        double dir = forward ? 1.0 : -1.0;
        if (thisConj) {
            testDate.shour += 0.2 * dir;
            testDate.normalizeHours();
        }

        double jday = testDate.getJulianComplete();
        MoonData moon = new MoonData();
        GcGregorianDate d = new GcGregorianDate(testDate);
        GcGregorianDate xd = new GcGregorianDate();
        double scanStep = 1.0;
        int prevTit;
        int newTit = -1;

        moon.calculate(jday, earth);
        double sunl = SunData.getSunLongitude(d);
        double l1 = GcMath.putIn180(moon.longitudeDeg - sunl);
        prevTit = (int) Math.floor(l1 / phi);

        int counter = 0;
        while (counter < 20) {
            double xj = jday;
            xd.set(d);

            jday += scanStep * dir;
            d.shour += scanStep * dir;
            d.normalizeHours();

            moon.calculate(jday, earth);
            sunl = SunData.getSunLongitude(d);
            double l2 = GcMath.putIn180(moon.longitudeDeg - sunl);
            newTit = (int) Math.floor(l2 / phi);

            boolean isChange;
            if (forward) {
                isChange = prevTit < 0 && newTit >= 0;
            } else {
                isChange = prevTit >= 0 && newTit < 0;
            }

            if (isChange) {
                jday = xj;
                d.set(xd);
                scanStep *= 0.5;
                counter += 1;
            } else {
                l1 = l2;
                prevTit = newTit;
            }
        }

        found.set(d);
        return sunl;
    }

    static double getNextConjunctionEx(GcGregorianDate testDate, GcGregorianDate found,
            boolean thisConj, EarthData earth) {
        return getPrevConjunctionEx(testDate, found, thisConj, earth, true);
    }
}
