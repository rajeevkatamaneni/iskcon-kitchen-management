package org.iskcon.kms.calendar.engine;

import org.iskcon.kms.calendar.astro.EarthData;
import org.iskcon.kms.calendar.astro.GcGregorianDate;
import org.iskcon.kms.calendar.astro.GcMath;
import org.iskcon.kms.calendar.astro.MoonData;
import org.iskcon.kms.calendar.astro.SunData;

/** Faithful port of GCTithi.py (only the pieces used by calendar building). */
final class GcTithi {

    private GcTithi() {
    }

    static boolean tithiEkadasi(int a) {
        return a == 10 || a == 25;
    }

    static boolean tithiDvadasi(int a) {
        return a == 11 || a == 26;
    }

    static boolean tithiTrayodasi(int a) {
        return a == 12 || a == 27;
    }

    static boolean tithiCaturdasi(int a) {
        return a == 13 || a == 28;
    }

    static boolean tithiLessEkadasi(int a) {
        return a == 9 || a == 24 || a == 8 || a == 23;
    }

    static boolean tithiFullNewMoon(int a) {
        return a == 14 || a == 29;
    }

    static int prevPrevTithi(int a) {
        return (a + 28) % 30;
    }

    static int prevTithi(int a) {
        return (a + 29) % 30;
    }

    static int nextTithi(int a) {
        return (a + 1) % 30;
    }

    static int nextNextTithi(int a) {
        return (a + 2) % 30;
    }

    static boolean tithiLessThan(int a, int b) {
        return a == prevTithi(b) || a == prevPrevTithi(b);
    }

    static boolean tithiGreatThan(int a, int b) {
        return a == nextTithi(b) || a == nextNextTithi(b);
    }

    /** TRUE when transit from A to B is between T and U. */
    static boolean tithiTransit(int t, int u, int a, int b) {
        return ((t == a) && (u == b)) || ((t == a) && (u == nextTithi(b)))
                || ((t == prevTithi(a)) && (u == b));
    }

    static int getNextTithiStart(EarthData ed, GcGregorianDate startDate, GcGregorianDate nextDate,
            boolean forward) {
        double phi = 12.0;
        double jday = startDate.getJulianComplete();
        MoonData moon = new MoonData();
        GcGregorianDate d = new GcGregorianDate(startDate);
        GcGregorianDate xd = new GcGregorianDate();
        double dir = forward ? 1.0 : -1.0;
        double scanStep = 0.5 * dir;
        int prevTit;
        int newTit = -1;

        moon.calculate(jday, ed);
        double sunl = SunData.getSunLongitude(d);
        double l1 = GcMath.putIn360(moon.longitudeDeg - sunl - 180.0);
        prevTit = (int) Math.floor(l1 / phi);

        int counter = 0;
        while (counter < 20) {
            double xj = jday;
            xd.set(d);

            jday += scanStep;
            d.shour += scanStep;
            d.normalizeHours();

            moon.calculate(jday, ed);
            sunl = SunData.getSunLongitude(d);
            double l2 = GcMath.putIn360(moon.longitudeDeg - sunl - 180.0);
            newTit = (int) Math.floor(l2 / phi);

            if (prevTit != newTit) {
                jday = xj;
                d.set(xd);
                scanStep *= 0.5;
                counter += 1;
            } else {
                l1 = l2;
            }
        }
        nextDate.set(d);
        return newTit;
    }

    static int getNextTithiStart(EarthData ed, GcGregorianDate startDate, GcGregorianDate nextDate) {
        return getNextTithiStart(ed, startDate, nextDate, true);
    }

    static int getPrevTithiStart(EarthData ed, GcGregorianDate startDate, GcGregorianDate nextDate) {
        return getNextTithiStart(ed, startDate, nextDate, false);
    }

    /**
     * Mirror of GetTithiTimes.
     *
     * @return {@code double[]{length, titBeg, titEnd}}
     */
    static double[] getTithiTimes(EarthData ed, GcGregorianDate vc, double sunRise) {
        GcGregorianDate d1 = new GcGregorianDate();
        GcGregorianDate d2 = new GcGregorianDate();

        vc.shour = sunRise;
        getPrevTithiStart(ed, vc, d1);
        getNextTithiStart(ed, vc, d2);

        double titBeg = d1.shour + d1.getJulian() - vc.getJulian();
        double titEnd = d2.shour + d2.getJulian() - vc.getJulian();

        return new double[] {titEnd - titBeg, titBeg, titEnd};
    }
}
