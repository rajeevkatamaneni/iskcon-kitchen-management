package org.iskcon.kms.calendar.astro;

/**
 * Faithful port of GCEarthData.py (the EARTHDATA class plus the module-level
 * functions, which here are static methods).
 */
public class EarthData {

    // observated event
    // 0 - center of the sun
    // 1 - civil twilight
    // 2 - nautical twilight
    // 3 - astronomical twilight
    public int obs = 0;
    public double longitudeDeg = 0.0;
    public double latitudeDeg = 0.0;
    public double tzone = 0.0;
    public int dst = 0;

    /** Result holder for {@link #getNextAscendentStart(GcGregorianDate)}. */
    public static final class NextAscendent {
        public final int rasi;
        public final GcGregorianDate date;

        public NextAscendent(int rasi, GcGregorianDate date) {
            this.rasi = rasi;
            this.date = date;
        }
    }

    /**
     * Port of calc_epsilon_phi.
     *
     * @return {@code double[]{deltaPhi, epsilon}} — index 0 is delta_phi, index 1
     *         is epsilon (both in degrees).
     */
    public static double[] calcEpsilonPhi(double date) {
        int[][] argMul = {
            {0, 0, 0, 0, 1},
            {-2, 0, 0, 2, 2},
            {0, 0, 0, 2, 2},
            {0, 0, 0, 0, 2},
            {0, 1, 0, 0, 0},
            {0, 0, 1, 0, 0},
            {-2, 1, 0, 2, 2},
            {0, 0, 0, 2, 1},
            {0, 0, 1, 2, 2},
            {-2, -1, 0, 2, 2},
            {-2, 0, 1, 0, 0},
            {-2, 0, 0, 2, 1},
            {0, 0, -1, 2, 2},
            {2, 0, 0, 0, 0},
            {0, 0, 1, 0, 1},
            {2, 0, -1, 2, 2},
            {0, 0, -1, 0, 1},
            {0, 0, 1, 2, 1},
            {-2, 0, 2, 0, 0},
            {0, 0, -2, 2, 1},
            {2, 0, 0, 2, 2},
            {0, 0, 2, 2, 2},
            {0, 0, 2, 0, 0},
            {-2, 0, 1, 2, 2},
            {0, 0, 0, 2, 0},
            {-2, 0, 0, 2, 0},
            {0, 0, -1, 2, 1},
            {0, 2, 0, 0, 0},
            {2, 0, -1, 0, 1},
            {-2, 2, 0, 2, 2},
            {0, 1, 0, 0, 1}
        };
        int[][] argPhi = {
            {-171996, -1742},
            {-13187, -16},
            {-2274, -2},
            {2062, 2},
            {1426, -34},
            {712, 1},
            {-517, 12},
            {-386, -4},
            {-301, 0},
            {217, -5},
            {-158, 0},
            {129, 1},
            {123, 0},
            {63, 0},
            {63, 1},
            {-59, 0},
            {-58, -1},
            {-51, 0},
            {48, 0},
            {46, 0},
            {-38, 0},
            {-31, 0},
            {29, 0},
            {29, 0},
            {26, 0},
            {-22, 0},
            {21, 0},
            {17, -1},
            {16, 0},
            {-16, 1},
            {-15, 0}
        };
        int[][] argEps = {
            {92025, 89},
            {5736, -31},
            {977, -5},
            {-895, 5},
            {54, -1},
            {-7, 0},
            {224, -6},
            {200, 0},
            {129, -1},
            {-95, 3},
            {0, 0},
            {-70, 0},
            {-53, 0},
            {0, 0},
            {-33, 0},
            {26, 0},
            {32, 0},
            {27, 0},
            {0, 0},
            {-24, 0},
            {16, 0},
            {13, 0},
            {0, 0},
            {-12, 0},
            {0, 0},
            {0, 0},
            {-10, 0},
            {0, 0},
            {-8, 0},
            {7, 0},
            {9, 0}
        };

        double t = (date - 2451545.0) / 36525;
        double deltaPhi;
        double deltaEpsilon;

        // longitude of rising knot
        double omega = GcMath.putIn360(125.04452 + (-1934.136261 + (0.0020708 + 1.0 / 450000 * t) * t) * t);

        if (true) {
            double l = 280.4665 + 36000.7698 * t;
            double ls = 218.3165 + 481267.8813 * t;

            deltaEpsilon = 9.20 * GcMath.cosDeg(omega) + 0.57 * GcMath.cosDeg(2 * l)
                    + 0.10 * GcMath.cosDeg(2 * ls) - 0.09 * GcMath.cosDeg(2 * omega);

            deltaPhi = (-17.20 * GcMath.sinDeg(omega) - 1.32 * GcMath.sinDeg(2 * l)
                    - 0.23 * GcMath.sinDeg(2 * ls) + 0.21 * GcMath.sinDeg(2 * omega)) / 3600;
        } else {
            // mean elongation of moon to sun
            double d = GcMath.putIn360(297.85036 + (445267.111480 + (-0.0019142 + t / 189474) * t) * t);
            // mean anomaly of the sun
            double m = GcMath.putIn360(357.52772 + (35999.050340 + (-0.0001603 - t / 300000) * t) * t);
            // mean anomaly of the moon
            double ms = GcMath.putIn360(134.96298 + (477198.867398 + (0.0086972 + t / 56250) * t) * t);
            // argument of the latitude of the moon
            double f = GcMath.putIn360(93.27191 + (483202.017538 + (-0.0036825 + t / 327270) * t) * t);

            deltaPhi = 0;
            deltaEpsilon = 0;

            for (int i = 0; i < 31; i++) {
                double s = argMul[i][0] * d + argMul[i][1] * m + argMul[i][2] * ms
                        + argMul[i][3] * f + argMul[i][4] * omega;
                deltaPhi = deltaPhi + (argPhi[i][0] + argPhi[i][1] * t * 0.1) * GcMath.sinDeg(s);
                deltaEpsilon = deltaEpsilon + (argEps[i][0] + argEps[i][1] * t * 0.1) * GcMath.cosDeg(s);
            }

            deltaPhi = deltaPhi * 0.0001 / 3600;
            deltaEpsilon = deltaEpsilon * 0.0001 / 3600;
        }

        // angle of ecliptic
        double epsilon0 = 84381.448 + (-46.8150 + (-0.00059 + 0.001813 * t) * t) * t;

        double epsilon = (epsilon0 + deltaEpsilon) / 3600;

        return new double[] {deltaPhi, epsilon};
    }

    public static GcCoords.EquatorialCoords eclipticalToEquatorialCoords(GcCoords.EclipticalCoords ecc, double date) {
        GcCoords.EquatorialCoords eqc = new GcCoords.EquatorialCoords();

        double[] ep = calcEpsilonPhi(date);
        double deltaPhi = ep[0];
        double epsilon = ep[1];

        ecc.longitude = GcMath.putIn360(ecc.longitude + deltaPhi);

        eqc.rightAscension = GcMath.arcTan2Deg(
                GcMath.sinDeg(ecc.longitude) * GcMath.cosDeg(epsilon)
                        - GcMath.tanDeg(ecc.latitude) * GcMath.sinDeg(epsilon),
                GcMath.cosDeg(ecc.longitude));

        eqc.declination = GcMath.arcSinDeg(
                GcMath.sinDeg(ecc.latitude) * GcMath.cosDeg(epsilon)
                        + GcMath.cosDeg(ecc.latitude) * GcMath.sinDeg(epsilon) * GcMath.sinDeg(ecc.longitude));

        return eqc;
    }

    public static GcCoords.HorizontalCoords equatorialToHorizontalCoords(
            GcCoords.EquatorialCoords eqc, EarthData obs, double date) {
        GcCoords.HorizontalCoords hc = new GcCoords.HorizontalCoords();

        double h = GcMath.putIn360(starTime(date) - eqc.rightAscension + obs.longitudeDeg);

        hc.azimut = GcMath.rad2deg(Math.atan2(GcMath.sinDeg(h),
                GcMath.cosDeg(h) * GcMath.sinDeg(obs.latitudeDeg)
                        - GcMath.tanDeg(eqc.declination) * GcMath.cosDeg(obs.latitudeDeg)));

        hc.elevation = GcMath.rad2deg(Math.asin(
                GcMath.sinDeg(obs.latitudeDeg) * GcMath.sinDeg(eqc.declination)
                        + GcMath.cosDeg(obs.latitudeDeg) * GcMath.cosDeg(eqc.declination) * GcMath.cosDeg(h)));

        return hc;
    }

    public static String getTextLatitude(double d) {
        char c0 = d < 0.0 ? 'S' : 'N';
        d = Math.abs(d);
        int a0 = (int) Math.floor(d);
        int a1 = (int) Math.floor((d - a0) * 60 + 0.5);
        return String.format("%d%s%02d", a0, c0, a1);
    }

    public static String getTextLongitude(double d) {
        char c0 = d < 0.0 ? 'W' : 'E';
        d = Math.abs(d);
        int a0 = (int) Math.floor(d);
        int a1 = (int) Math.floor((d - a0) * 60 + 0.5);
        return String.format("%d%s%02d", a0, c0, a1);
    }

    public static double starTime(double date) {
        double jd = date;
        double t = (jd - 2451545.0) / 36525.0;
        double[] ep = calcEpsilonPhi(date);
        double deltaPhi = ep[0];
        double epsilon = ep[1];
        return GcMath.putIn360(280.46061837 + 360.98564736629 * (jd - 2451545.0)
                + t * t * (0.000387933 - t / 38710000)
                + deltaPhi * GcMath.cosDeg(epsilon));
    }

    public double getHorizontDegrees(double jday) {
        return GcMath.putIn360(starTime(jday) - this.longitudeDeg - GcAyanamsha.getAyanamsa(jday) + 155);
    }

    public NextAscendent getNextAscendentStart(GcGregorianDate startDate) {
        double phi = 30.0;
        double l1;
        double l2;
        double jday = startDate.getJulianComplete();
        double xj;
        GcGregorianDate d = new GcGregorianDate(startDate);
        GcGregorianDate xd = new GcGregorianDate();
        double scanStep = 0.05;
        int prevTit;
        int newTit = -1;

        l1 = getHorizontDegrees(jday);

        prevTit = (int) Math.floor(l1 / phi);

        int counter = 0;
        while (counter < 20) {
            xj = jday;
            xd.set(d);

            jday += scanStep;
            d.shour += scanStep;
            if (d.shour > 1.0) {
                d.shour -= 1.0;
                d.nextDay();
            }

            l2 = getHorizontDegrees(jday);
            newTit = (int) Math.floor(l2 / phi);

            if (prevTit != newTit) {
                jday = xj;
                d.set(xd);
                scanStep *= 0.5;
                counter += 1;
                continue;
            } else {
                l1 = l2;
            }
        }

        GcGregorianDate nextDate = new GcGregorianDate(d);
        return new NextAscendent(newTit, nextDate);
    }
}
