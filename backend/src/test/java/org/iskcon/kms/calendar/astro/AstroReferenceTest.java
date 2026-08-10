package org.iskcon.kms.calendar.astro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Asserts the numeric expectations from the GCAL Python reference {@code unittests()}
 * functions (GCMath, GCTime, GCAyanamsha, GCEarthData) plus deterministic
 * regression values captured from the reference for GCSunData and GCMoonData
 * (whose own {@code unittests()} only print current-time values).
 *
 * <p>Plain JUnit 5 — no Spring, no Testcontainers.
 */
class AstroReferenceTest {

    // ---- GCMath.unittests() -------------------------------------------------

    @Test
    void gcMath() {
        assertThat(GcMath.sinDeg(90)).isCloseTo(1.0, within(1e-9));
        assertThat(GcMath.cosDeg(0)).isCloseTo(1.0, within(1e-9));
        assertThat(GcMath.putIn24(30)).isCloseTo(6.0, within(1e-9));
        assertThat(GcMath.floor(1.3)).isCloseTo(1.0, within(1e-9));
        assertThat(GcMath.floor(-1.3)).isCloseTo(-2.0, within(1e-9));
    }

    // ---- GCTime.unittests() -------------------------------------------------

    @Test
    void gcTime() {
        GcTime dt1 = new GcTime(1, 23, 0, 0);
        GcTime dt2 = new GcTime(2, 30, 0, 0);
        GcTime dt3 = new GcTime(5, 10, 0, 0);

        assertThat(dt1.toLongTimeString()).isEqualTo("01:23:00");
        assertThat(dt3.isGreaterThan(dt2)).isTrue();

        dt2.addMinutes(176);
        assertThat(dt2.toLongTimeString()).isEqualTo("05:26:00");
        assertThat(dt3.isLessThan(dt2)).isTrue();
    }

    // ---- GCGregorianDate (deterministic Julian-day checks) ------------------

    @Test
    void gcGregorianDate() {
        GcGregorianDate d = new GcGregorianDate(2000, 1, 1, 0.5);
        assertThat(d.getJulianInteger()).isEqualTo(2451545);
        assertThat(d.getJulianComplete()).isCloseTo(2451545.0, within(1e-9));
        assertThat(d.getJulianDetailed()).isCloseTo(2451545.0, within(1e-9));
    }

    // ---- GCAyanamsha.unittests() --------------------------------------------

    @Test
    void gcAyanamsha() {
        // nval: name must be non-empty for the default (Lahiri) type
        assertThat(GcAyanamsha.getAyanamsaName(GcAyanamsha.getAyanamsaType())).isNotEmpty();
        // nval: ayanamsha value must be non-zero
        assertThat(GcAyanamsha.getAyanamsa(2000000)).isNotEqualTo(0.0);
        // exact captured value
        assertThat(GcAyanamsha.getAyanamsa(2000000)).isCloseTo(6.536920276999975, within(1e-9));
    }

    // ---- GCEarthData.unittests() --------------------------------------------

    @Test
    void gcEarthDataText() {
        assertThat(EarthData.getTextLatitude(12.5)).isEqualTo("12N30");
        assertThat(EarthData.getTextLongitude(-15.25)).isEqualTo("15W15");
    }

    @Test
    void gcEarthDataStarTimeAndNutation() {
        assertThat(EarthData.starTime(2451545.0)).isCloseTo(280.45704234942144, within(1e-9));

        double[] ep = EarthData.calcEpsilonPhi(2451545.0);
        // ep[0] = delta_phi, ep[1] = epsilon
        assertThat(ep[0]).isCloseTo(-0.0038975991170544155, within(1e-9));
        assertThat(ep[1]).isCloseTo(23.437690731210242, within(1e-9));
    }

    // ---- GCSunData (deterministic regression values) ------------------------

    @Test
    void sunTables() {
        assertThat(SunData.sunGetMeanLong(2000, 1, 1)).isCloseTo(280.46, within(1e-6));
        assertThat(SunData.sunGetPerigee(2000, 1, 1)).isCloseTo(282.93, within(1e-6));
        assertThat(SunData.getSunLongitude(new GcGregorianDate(2000, 1, 1, 0.5)))
                .isCloseTo(280.3757483722648, within(1e-6));
    }

    @Test
    void sunCalc() {
        EarthData e = new EarthData();
        e.longitudeDeg = 27.0;
        e.latitudeDeg = 45.0;
        e.tzone = 1.0;
        GcGregorianDate vc = new GcGregorianDate(2000, 6, 21, 0.5, 1.0);

        SunData s = new SunData();
        s.sunCalc(vc, e);

        assertThat(s.sunriseDeg).isCloseTo(36.30900945834456, within(1e-6));
        assertThat(s.sunsetDeg).isCloseTo(270.59508322016205, within(1e-6));
        assertThat(s.longitudeDeg).isCloseTo(90.03215550090624, within(1e-6));
        assertThat(s.lengthDeg).isCloseTo(234.28607376181748, within(1e-6));

        // Use toLongTimeString() (HH:MM:SS) which matches Python's ToLongTimeString.
        // The Python repr's milliseconds are unreliable due to the self.mili/self.milli
        // field bug (repr reads the never-written 'milli', always 0); the unified-field
        // Java port instead carries the real milliseconds.
        assertThat(s.rise.toLongTimeString()).isEqualTo("03:25:14");
        assertThat(s.noon.toLongTimeString()).isEqualTo("11:13:48");
        assertThat(s.set.toLongTimeString()).isEqualTo("19:02:22");
        assertThat(s.length.toLongTimeString()).isEqualTo("15:37:08");
    }

    // ---- GCMoonData (deterministic regression values) -----------------------

    @Test
    void moonDistance() {
        GcGregorianDate vc = new GcGregorianDate(2000, 1, 1, 0.5);
        assertThat(MoonData.moonDistance(vc.getJulianComplete()))
                .isCloseTo(402444.81551266526, within(1e-6));
    }

    @Test
    void moonCalculateEcliptical() {
        MoonData m = new MoonData();
        GcCoords.EclipticalCoords c = m.calculateEcliptical(2451545.0);
        assertThat(c.longitude).isCloseTo(223.31872110644292, within(1e-6));
        assertThat(c.latitude).isCloseTo(5.171279988646132, within(1e-6));
        assertThat(c.distance).isCloseTo(402444.81551266526, within(1e-6));
    }
}
