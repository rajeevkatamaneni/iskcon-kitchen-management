package org.iskcon.kms.calendar.astro;

/**
 * Faithful port of GCMath.py from the Gaudiya-Vaishnava calendar program GCAL.
 * All angle-based helpers take/return degrees unless otherwise noted.
 */
public final class GcMath {

    private GcMath() {
    }

    /** pi */
    public static final double PI = 3.1415926535897932385;

    /** 2 * pi */
    public static final double PI2 = 6.2831853071795864769;

    /** pi / 180 */
    public static final double RADS = 0.0174532925199432958;

    /** distance of Earth from Sun in km */
    public static final double AU = 149597869.0;

    /** astronomy data */
    public static final double EARTH_RADIUS = 6378.15;
    public static final double MOON_RADIUS = 1737.4;
    public static final double SUN_RADIUS = 695500;

    /** julian day constants */
    public static final double J1999 = 2451180.0;
    public static final double J2000 = 2451545.0;

    // input value: arc in degrees
    public static double cosDeg(double x) {
        return Math.cos(x * RADS);
    }

    // input value: arc in degrees
    public static double sinDeg(double x) {
        return Math.sin(x * RADS);
    }

    public static double arccosDeg(double x) {
        return Math.acos(x) / RADS;
    }

    // input value: arc in degrees
    // it is calculating arctan(x/y) with testing values
    public static double arcTan2Deg(double x, double y) {
        return Math.atan2(x, y) / RADS;
    }

    // input value: arc in degrees
    // output value: tangens
    public static double tanDeg(double x) {
        return Math.tan(x * RADS);
    }

    // input value: -1.0 .. 1.0
    // output value: -180 deg .. 180 deg
    public static double arcSinDeg(double x) {
        return Math.asin(x) / RADS;
    }

    public static double arcTanDeg(double x) {
        return Math.atan(x) / RADS;
    }

    // modulo 1
    public static double putIn1(double v) {
        double v2 = v - Math.floor(v);
        while (v2 < 0.0) {
            v2 += 1.0;
        }
        while (v2 > 1.0) {
            v2 -= 1.0;
        }
        return v2;
    }

    // modulo 24
    public static double putIn24(double id) {
        double d = id;
        while (d >= 24.0) {
            d -= 24.0;
        }
        while (d < 0.0) {
            d += 24.0;
        }
        return d;
    }

    // modulo 360
    public static double putIn360(double id) {
        double d = id;
        while (d >= 360.0) {
            d -= 360.0;
        }
        while (d < 0.0) {
            d += 360.0;
        }
        return d;
    }

    // modulo 360 but in range -180deg .. 180deg
    public static double putIn180(double in_d) {
        double d = in_d;
        while (d < -180.0) {
            d += 360.0;
        }
        while (d > 180.0) {
            d -= 360.0;
        }
        return d;
    }

    // sign of the number: -1, 0, +1
    public static int getSign(double d) {
        if (d > 0.0) {
            return 1;
        }
        if (d < 0.0) {
            return -1;
        }
        return 0;
    }

    public static double deg2rad(double x) {
        return x * RADS;
    }

    public static double rad2deg(double x) {
        return x / RADS;
    }

    public static double getFraction(double x) {
        return x - Math.floor(x);
    }

    public static double floor(double d) {
        return Math.floor(d);
    }

    public static double arcDistance(double lon1, double lat1, double lon2, double lat2) {
        lat1 = PI / 2 - lat1;
        lat2 = PI / 2 - lat2;
        return arccosDeg(cosDeg(lat1) * cosDeg(lat2) + sinDeg(lat1) * sinDeg(lat2) * cosDeg(lon1 - lon2));
    }

    public static double arcDistanceDeg(double lon1, double lat1, double lon2, double lat2) {
        return rad2deg(arcDistance(deg2rad(lon1), deg2rad(lat1), deg2rad(lon2), deg2rad(lat2)));
    }
}
