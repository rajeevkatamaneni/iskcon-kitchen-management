package org.iskcon.kms.calendar.engine;

import org.iskcon.kms.calendar.astro.GcAyanamsha;
import org.iskcon.kms.calendar.astro.GcGregorianDate;
import org.iskcon.kms.calendar.astro.GcMath;
import org.iskcon.kms.calendar.astro.SunData;

/** Faithful port of GCSankranti.py (sankranti-determine type fixed at 2 = noon-to-noon). */
final class GcSankranti {

    private GcSankranti() {
    }

    static final int SANKRANTI_TYPE = 2;

    /** Result holder mirroring the (date, zodiac) tuple returned by GetNextSankranti. */
    static final class Result {
        final GcGregorianDate date;
        final int zodiac;

        Result(GcGregorianDate date, int zodiac) {
            this.date = date;
            this.zodiac = zodiac;
        }
    }

    static Result getNextSankranti(GcGregorianDate startDate) {
        int zodiac = 0;
        double step = 1.0;
        int count = 0;
        GcGregorianDate prevday = new GcGregorianDate();

        GcGregorianDate d = new GcGregorianDate(startDate);

        double prev = GcMath.putIn360(
                SunData.getSunLongitude(d) - GcAyanamsha.getAyanamsa(d.getJulian()));
        int prevRasi = (int) Math.floor(prev / 30.0);

        while (count < 20) {
            prevday.set(d);
            d.shour += step;
            d.normalizeHours();

            double ld = GcMath.putIn360(
                    SunData.getSunLongitude(d) - GcAyanamsha.getAyanamsa(d.getJulian()));
            int newRasi = (int) Math.floor(ld / 30.0);

            if (prevRasi != newRasi) {
                zodiac = newRasi;
                step *= 0.5;
                d.set(prevday);
                count += 1;
            }
        }

        return new Result(d, zodiac);
    }
}
