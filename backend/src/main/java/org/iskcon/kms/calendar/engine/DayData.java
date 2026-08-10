package org.iskcon.kms.calendar.engine;

import org.iskcon.kms.calendar.astro.EarthData;
import org.iskcon.kms.calendar.astro.GcAyanamsha;
import org.iskcon.kms.calendar.astro.GcGregorianDate;
import org.iskcon.kms.calendar.astro.GcMath;
import org.iskcon.kms.calendar.astro.MoonData;
import org.iskcon.kms.calendar.astro.SunData;

/** Faithful port of GCDayData.py (DayCalc + MasaCalc). */
final class DayData {

    double jdate = 0.0;
    final SunData sun = new SunData();
    final MoonData moon = new MoonData();
    int nGaurabdaYear = 0;
    double msAyanamsa = 0;
    int nSunRasi = 0;
    int nMoonRasi = 0;
    int nTithi = 0;
    int nTithiArunodaya = 0;
    int nTithiSunset = 0;
    double nTithiElapse = 0.0;
    int nYoga = 0;
    double nYogaElapse = 0.0;
    int nNaksatra = 0;
    double nNaksatraElapse = 0.0;
    int nMasa = 0;
    double msDistance = 0.0;

    int getPaksa() {
        return nTithi >= 15 ? 1 : 0;
    }

    int dayCalc(GcGregorianDate date, EarthData earth) {
        // sun position on sunrise on that day
        this.sun.sunCalc(date, earth);
        date.shour = this.sun.sunriseDeg / 360.0;

        this.jdate = date.getJulianDetailed();
        double jd = this.jdate;

        // moon position at sunrise on that day
        this.moon.calculate(date.getJulianDetailed(), earth);

        this.msDistance = GcMath.putIn360(this.moon.longitudeDeg - this.sun.longitudeDeg - 180.0);
        this.msAyanamsa = GcAyanamsha.getAyanamsa(jd);

        // tithi
        double d = this.msDistance / 12.0;
        this.nTithi = (int) Math.floor(d);
        this.nTithiElapse = (d - Math.floor(d)) * 100.0;

        // naksatra
        d = GcMath.putIn360(this.moon.longitudeDeg - this.msAyanamsa);
        d = (d * 3.0) / 40.0;
        this.nNaksatra = (int) Math.floor(d);
        this.nNaksatraElapse = (d - Math.floor(d)) * 100.0;

        // yoga
        d = GcMath.putIn360(this.moon.longitudeDeg + this.sun.longitudeDeg - 2 * this.msAyanamsa);
        d = (d * 3.0) / 40.0;
        this.nYoga = (int) Math.floor(d);
        this.nYogaElapse = (d - Math.floor(d)) * 100.0;

        // masa
        this.nMasa = -1;

        // rasi
        this.nSunRasi = GcRasi.getRasi(this.sun.longitudeDeg, this.msAyanamsa);
        this.nMoonRasi = GcRasi.getRasi(this.moon.longitudeDeg, this.msAyanamsa);

        MoonData m = new MoonData();
        date.shour = this.sun.sunsetDeg / 360.0;
        m.calculate(date.getJulianDetailed(), earth);
        d = GcMath.putIn360(m.longitudeDeg - this.sun.longitudeSetDeg - 180) / 12.0;
        this.nTithiSunset = (int) Math.floor(d);

        date.shour = this.sun.arunodayaDeg / 360.0;
        m.calculate(date.getJulianDetailed(), earth);
        d = GcMath.putIn360(m.longitudeDeg - this.sun.longitudeArunDeg - 180) / 12.0;
        this.nTithiArunodaya = (int) Math.floor(d);

        return 1;
    }

    int masaCalc(GcGregorianDate date, EarthData earth) {
        final int prevMonths = 6;
        double[] L = new double[8];
        GcGregorianDate[] C = new GcGregorianDate[8];
        for (int i = 0; i < 8; i++) {
            C[i] = new GcGregorianDate();
        }
        int[] R = new int[8];

        date.shour = this.sun.sunriseDeg / 360.0 + earth.tzone / 24.0;

        L[1] = GcConjunction.getNextConjunctionEx(date, C[1], false, earth);
        L[0] = GcConjunction.getNextConjunctionEx(C[1], C[0], true, earth);

        L[2] = GcConjunction.getPrevConjunctionEx(date, C[2], false, earth, false);

        for (int n = 3; n < prevMonths; n++) {
            L[n] = GcConjunction.getPrevConjunctionEx(C[n - 1], C[n], true, earth, false);
        }

        for (int n = 0; n < prevMonths; n++) {
            R[n] = GcRasi.getRasi(L[n], GcAyanamsha.getAyanamsa(C[n].getJulian()));
        }

        // look for ksaya month
        int ksayaFrom = -1;
        for (int n = prevMonths - 2; n > 0; n--) {
            if ((R[n + 1] + 2) % 12 == R[n]) {
                ksayaFrom = n;
                break;
            }
        }

        if (ksayaFrom >= 0) {
            int ksayaTo = -1;
            for (int n = ksayaFrom; n > 0; n--) {
                if (R[n] == R[n - 1]) {
                    ksayaTo = n;
                    break;
                }
            }

            if (ksayaTo < 0) {
                ksayaTo = 0;
            }

            int currentRasi = R[ksayaFrom + 1] + 1;
            for (int n = ksayaFrom; n > ksayaTo - 1; n--) {
                R[n] = currentRasi;
                currentRasi = (currentRasi + 1) % 12;
            }
        }

        // test for adhika masa
        int masa;
        int rasi;
        if (R[1] == R[2]) {
            masa = 12;
            rasi = R[1];
        } else {
            if (getPaksa() == 0) {
                masa = R[1];
            } else {
                masa = R[2];
            }
            rasi = masa;
        }

        // calculation of Gaurabda year
        this.nGaurabdaYear = date.year - 1486;

        if ((rasi > 7) && (rasi < 11)) { // Visnu
            if (date.month < 6) {
                this.nGaurabdaYear -= 1;
            }
        }

        this.nMasa = masa;

        return masa;
    }
}
