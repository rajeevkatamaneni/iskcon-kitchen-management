package org.iskcon.kms.calendar.astro;

/**
 * Faithful port of GCMoonData.py (the MOONDATA class plus the module-level
 * functions, which here are static methods).
 */
public class MoonData {

    // latitude from nodal (draconic) phase
    public double latitudeDeg = 0.0;
    // longitude from sidereal motion
    public double longitudeDeg = 0.0;
    public double radius = 0.0;
    public double rektaszension = 0.0;
    public double declination = 0.0;
    public double parallax = 0.0;
    public double elevation = 0.0;
    public double azimuth = 0.0;

    /** Result holder for {@link #getNextMoonRasi(EarthData, GcGregorianDate)}. */
    public static final class NextMoonRasi {
        public final int rasi;
        public final GcGregorianDate date;

        public NextMoonRasi(int rasi, GcGregorianDate date) {
            this.rasi = rasi;
            this.date = date;
        }
    }

    /** Result holder for {@link #calcMoonTimes(EarthData, GcGregorianDate, double)}. */
    public static final class MoonTimes {
        public final GcTime rise;
        public final GcTime set;

        public MoonTimes(GcTime rise, GcTime set) {
            this.rise = rise;
            this.set = set;
        }
    }

    public GcCoords.EclipticalCoords calculateEcliptical(double jdate) {
        int[][] argLr = {
            {0, 0, 1, 0}, {2, 0, -1, 0}, {2, 0, 0, 0}, {0, 0, 2, 0},
            {0, 1, 0, 0}, {0, 0, 0, 2}, {2, 0, -2, 0}, {2, -1, -1, 0},
            {2, 0, 1, 0}, {2, -1, 0, 0}, {0, 1, -1, 0}, {1, 0, 0, 0},
            {0, 1, 1, 0}, {2, 0, 0, -2}, {0, 0, 1, 2}, {0, 0, 1, -2},
            {4, 0, -1, 0}, {0, 0, 3, 0}, {4, 0, -2, 0}, {2, 1, -1, 0},
            {2, 1, 0, 0}, {1, 0, -1, 0}, {1, 1, 0, 0}, {2, -1, 1, 0},
            {2, 0, 2, 0}, {4, 0, 0, 0}, {2, 0, -3, 0}, {0, 1, -2, 0},
            {2, 0, -1, 2}, {2, -1, -2, 0}, {1, 0, 1, 0}, {2, -2, 0, 0},
            {0, 1, 2, 0}, {0, 2, 0, 0}, {2, -2, -1, 0}, {2, 0, 1, -2},
            {2, 0, 0, 2}, {4, -1, -1, 0}, {0, 0, 2, 2}, {3, 0, -1, 0},
            {2, 1, 1, 0}, {4, -1, -2, 0}, {0, 2, -1, 0}, {2, 2, -1, 0},
            {2, 1, -2, 0}, {2, -1, 0, -2}, {4, 0, 1, 0}, {0, 0, 4, 0},
            {4, -1, 0, 0}, {1, 0, -2, 0}, {2, 1, 0, -2}, {0, 0, 2, -2},
            {1, 1, 1, 0}, {3, 0, -2, 0}, {4, 0, -3, 0}, {2, -1, 2, 0},
            {0, 2, 1, 0}, {1, 1, -1, 0}, {2, 0, 3, 0}, {2, 0, -1, -2}};

        int[][] argB = {
            {0, 0, 0, 1}, {0, 0, 1, 1}, {0, 0, 1, -1}, {2, 0, 0, -1},
            {2, 0, -1, 1}, {2, 0, -1, -1}, {2, 0, 0, 1}, {0, 0, 2, 1},
            {2, 0, 1, -1}, {0, 0, 2, -1}, {2, -1, 0, -1}, {2, 0, -2, -1},
            {2, 0, 1, 1}, {2, 1, 0, -1}, {2, -1, -1, 1}, {2, -1, 0, 1},
            {2, -1, -1, -1}, {0, 1, -1, -1}, {4, 0, -1, -1}, {0, 1, 0, 1},
            {0, 0, 0, 3}, {0, 1, -1, 1}, {1, 0, 0, 1}, {0, 1, 1, 1},
            {0, 1, 1, -1}, {0, 1, 0, -1}, {1, 0, 0, -1}, {0, 0, 3, 1},
            {4, 0, 0, -1}, {4, 0, -1, 1}, {0, 0, 1, -3}, {4, 0, -2, 1},
            {2, 0, 0, -3}, {2, 0, 2, -1}, {2, -1, 1, -1}, {2, 0, -2, 1},
            {0, 0, 3, -1}, {2, 0, 2, 1}, {2, 0, -3, -1}, {2, 1, -1, 1},
            {2, 1, 0, 1}, {4, 0, 0, 1}, {2, -1, 1, 1}, {2, -2, 0, -1},
            {0, 0, 1, 3}, {2, 1, 1, -1}, {1, 1, 0, -1}, {1, 1, 0, 1},
            {0, 1, -2, -1}, {2, 1, -1, -1}, {1, 0, 1, 1}, {2, -1, -2, -1},
            {0, 1, 2, 1}, {4, 0, -2, -1}, {4, -1, -1, -1}, {1, 0, 1, -1},
            {4, 0, 1, -1}, {1, 0, -1, -1}, {4, -1, 0, -1}, {2, -2, 0, 1}};

        int[] sigmaR = {
            -20905355, -3699111, -2955968, -569925,
            48888, -3149, 246158, -152138,
            -170733, -204586, -129620, 108743,
            104755, 10321, 0, 79661,
            -34782, -23210, -21636, 24208,
            30824, -8379, -16675, -12831,
            -10445, -11650, 14403, -7003,
            0, 10056, 6322, -9884,
            5751, 0, -4950, 4130,
            0, -3958, 0, 3258,
            2616, -1897, -2117, 2354,
            0, 0, -1423, -1117,
            -1571, -1739, 0, -4421,
            0, 0, 0, 0,
            1165, 0, 0, 8752};

        int[] sigmaL = {
            6288774, 1274027, 658314, 213618,
            -185116, -114332, 58793, 57066,
            53322, 45758, -40923, -34720,
            -30383, 15327, -12528, 10980,
            10675, 10034, 8548, -7888,
            -6766, -5163, 4987, 4036,
            3994, 3861, 3665, -2689,
            -2602, 2390, -2348, 2236,
            -2120, -2069, 2048, -1773,
            -1595, 1215, -1110, -892,
            -810, 759, -713, -700,
            691, 596, 549, 537,
            520, -487, -399, -381,
            351, -340, 330, 327,
            -323, 299, 294, 0};

        int[] sigmaB = {
            5128122, 280602, 277693, 173237,
            55413, 46271, 32573, 17198,
            9266, 8822, 8216, 4324,
            4200, -3359, 2463, 2211,
            2065, -1870, 1828, -1794,
            -1749, -1565, -1491, -1475,
            -1410, -1344, -1335, 1107,
            1021, 833, 777, 671,
            607, 596, 491, -451,
            439, 422, 421, -366,
            -351, 331, 315, 302,
            -283, -229, 223, 223,
            -220, -220, -185, 181,
            -177, 176, 166, -164,
            132, -119, 115, 107};

        double t = (jdate - 2451545.0) / 36525.0;

        // mean elongation of the moon
        double d = 297.8502042 + (445267.1115168 + (-0.0016300 + (1.0 / 545868 - 1.0 / 113065000 * t) * t) * t) * t;

        // mean anomaly of the sun
        double m = 357.5291092 + (35999.0502909 + (-0.0001536 + 1.0 / 24490000 * t) * t) * t;

        // mean anomaly of the moon
        double ms = 134.9634114 + (477198.8676313 + (0.0089970 + (1.0 / 69699 - 1.0 / 1471200 * t) * t) * t) * t;

        // argument of the longitude of the moon
        double f = 93.2720993 + (483202.0175273 + (-0.0034029 + (-1.0 / 3526000 + 1.0 / 863310000 * t) * t) * t) * t;

        // correction term due to excentricity of the earth orbit
        double e = 1.0 + (-0.002516 - 0.0000074 * t) * t;

        // mean longitude of the moon
        double ls = 218.3164591 + (481267.88134236 + (-0.0013268 + (1.0 / 538841 - 1.0 / 65194000 * t) * t) * t) * t;

        // arguments of correction terms
        double a1 = 119.75 + 131.849 * t;
        double a2 = 53.09 + 479264.290 * t;
        double a3 = 313.45 + 481266.484 * t;

        double sr = 0;
        for (int i = 0; i < 60; i++) {
            double temp = sigmaR[i] * GcMath.cosDeg(argLr[i][0] * d + argLr[i][1] * m + argLr[i][2] * ms + argLr[i][3] * f);
            if (Math.abs(argLr[i][1]) == 1) {
                temp = temp * e;
            }
            if (Math.abs(argLr[i][1]) == 2) {
                temp = temp * e * e;
            }
            sr = sr + temp;
        }

        double sl = 0;
        for (int i = 0; i < 60; i++) {
            double temp = sigmaL[i] * GcMath.sinDeg(argLr[i][0] * d + argLr[i][1] * m + argLr[i][2] * ms + argLr[i][3] * f);
            if (Math.abs(argLr[i][1]) == 1) {
                temp = temp * e;
            }
            if (Math.abs(argLr[i][1]) == 2) {
                temp = temp * e * e;
            }
            sl = sl + temp;
        }

        // correction terms
        sl = sl + 3958 * GcMath.sinDeg(a1)
                + 1962 * GcMath.sinDeg(ls - f)
                + 318 * GcMath.sinDeg(a2);

        double sb = 0;
        for (int i = 0; i < 60; i++) {
            double temp = sigmaB[i] * GcMath.sinDeg(argB[i][0] * d + argB[i][1] * m + argB[i][2] * ms + argB[i][3] * f);
            if (Math.abs(argB[i][1]) == 1) {
                temp = temp * e;
            }
            if (Math.abs(argB[i][1]) == 2) {
                temp = temp * e * e;
            }
            sb = sb + temp;
        }

        // correction terms
        sb = sb - 2235 * GcMath.sinDeg(ls) + 382 * GcMath.sinDeg(a3) + 175 * GcMath.sinDeg(a1 - f)
                + 175 * GcMath.sinDeg(a1 + f) + 127 * GcMath.sinDeg(ls - ms) - 115 * GcMath.sinDeg(ls + ms);

        GcCoords.EclipticalCoords coords = new GcCoords.EclipticalCoords();

        coords.longitude = ls + sl / 1000000;
        coords.latitude = sb / 1000000;
        coords.distance = 385000.56 + sr / 1000;

        return coords;
    }

    public void calculate(double jdate, EarthData earth) {
        GcCoords.EclipticalCoords crd = calculateEcliptical(jdate);
        GcCoords.EquatorialCoords eqc = EarthData.eclipticalToEquatorialCoords(crd, jdate);

        this.radius = crd.distance;
        this.longitudeDeg = crd.longitude;
        this.latitudeDeg = crd.latitude;

        this.rektaszension = eqc.rightAscension;
        this.declination = eqc.declination;
    }

    public void calcHorizontal(double date, double longitude, double latitude) {
        double h = GcMath.putIn360(EarthData.starTime(date) - this.rektaszension + longitude);

        this.azimuth = GcMath.rad2deg(Math.atan2(GcMath.sinDeg(h),
                GcMath.cosDeg(h) * GcMath.sinDeg(latitude) - GcMath.tanDeg(this.declination) * GcMath.cosDeg(latitude)));

        this.elevation = GcMath.rad2deg(Math.asin(
                GcMath.sinDeg(latitude) * GcMath.sinDeg(this.declination)
                        + GcMath.cosDeg(latitude) * GcMath.cosDeg(this.declination) * GcMath.cosDeg(h)));
    }

    public void correctPosition(double jdate, double latitude, double longitude, double height) {
        double ba = 0.99664719;

        double u = GcMath.arcTanDeg(ba * ba * GcMath.tanDeg(latitude));
        double rhoSin = ba * GcMath.sinDeg(u) + height / 6378140.0 * GcMath.sinDeg(latitude);
        double rhoCos = GcMath.cosDeg(u) + height / 6378140.0 * GcMath.cosDeg(latitude);

        this.parallax = GcMath.arcSinDeg(GcMath.sinDeg(8.794 / 3600) / (moonDistance(jdate) / GcMath.AU));

        double h = EarthData.starTime(jdate) - longitude - this.rektaszension;
        double deltaAlpha = GcMath.arcTanDeg(
                (-rhoCos * GcMath.sinDeg(this.parallax) * GcMath.sinDeg(h))
                        / (GcMath.cosDeg(this.declination) - rhoCos * GcMath.sinDeg(this.parallax) * GcMath.cosDeg(h)));
        this.rektaszension = this.rektaszension + deltaAlpha;
        this.declination = GcMath.arcTanDeg(
                ((GcMath.sinDeg(this.declination) - rhoSin * GcMath.sinDeg(this.parallax)) * GcMath.cosDeg(deltaAlpha))
                        / (GcMath.cosDeg(this.declination) - rhoCos * GcMath.sinDeg(this.parallax) * GcMath.cosDeg(h)));
    }

    public GcCoords.EquatorialCoords getTopocentricEquatorial(EarthData obs, double jdate) {
        double ba = 0.99664719;
        GcCoords.EquatorialCoords tec = new GcCoords.EquatorialCoords();

        double altitude = 0;

        // geocentric position of observer on the earth surface (10.1 - 10.3)
        double u = GcMath.arcTanDeg(ba * ba * GcMath.tanDeg(obs.latitudeDeg));
        double rhoSin = ba * GcMath.sinDeg(u) + altitude / 6378140.0 * GcMath.sinDeg(obs.latitudeDeg);
        double rhoCos = GcMath.cosDeg(u) + altitude / 6378140.0 * GcMath.cosDeg(obs.latitudeDeg);

        // equatorial horizontal paralax (39.1)
        // NOTE: mirrors the Python, which computes a local `parallax` here but then
        // uses self.parallax below.
        double parallaxLocal = GcMath.arcSinDeg(GcMath.sinDeg(8.794 / 3600) / (this.radius / GcMath.AU));

        // geocentric hour angle of the body
        double h = EarthData.starTime(jdate) + obs.longitudeDeg - this.rektaszension;

        // 39.2
        double deltaAlpha = GcMath.arcTanDeg(
                (-rhoCos * GcMath.sinDeg(this.parallax) * GcMath.sinDeg(h))
                        / (GcMath.cosDeg(this.declination) - rhoCos * GcMath.sinDeg(this.parallax) * GcMath.cosDeg(h)));
        tec.rightAscension = this.rektaszension + deltaAlpha;
        tec.declination = GcMath.arcTanDeg(
                ((GcMath.sinDeg(this.declination) - rhoSin * GcMath.sinDeg(this.parallax)) * GcMath.cosDeg(deltaAlpha))
                        / (GcMath.cosDeg(this.declination) - rhoCos * GcMath.sinDeg(this.parallax) * GcMath.cosDeg(h)));

        return tec;
    }

    public static double moonCalcElevation(EarthData e, GcGregorianDate vc) {
        MoonData moon = new MoonData();
        double d = vc.getJulianComplete();
        moon.calculate(d, e);
        moon.correctPosition(d, e.latitudeDeg, e.longitudeDeg, 0);
        moon.calcHorizontal(d, e.longitudeDeg, e.latitudeDeg);

        return moon.elevation;
    }

    public static MoonTimes calcMoonTimes(EarthData e, GcGregorianDate vc, double nDaylightSavingShift) {
        GcTime rise = new GcTime();
        GcTime set = new GcTime();

        double ut;
        double prevElev;
        int nType = 0;
        int nFound = 0;

        rise.setValue(-1);
        set.setValue(-1);

        // initialisation of the first ELEVATION value
        vc.shour = (-nDaylightSavingShift - 1.0) / 24.0;
        prevElev = moonCalcElevation(e, vc);

        double c = 0.0;

        // pass over all hours
        ut = -0.1 - nDaylightSavingShift;
        while (ut <= (24.1 - nDaylightSavingShift)) {
            vc.shour = ut / 24.0;
            double elev = moonCalcElevation(e, vc);
            if (prevElev * elev <= 0.0) {
                if (prevElev <= 0.0) {
                    nType = 0x1;
                } else {
                    nType = 0x2;
                }

                double a = ut - 1.0;
                double ae = prevElev;
                double b = ut;
                double be = elev;
                for (int i = 0; i < 20; i++) {
                    c = (a + b) / 2.0;
                    vc.shour = c / 24.0;
                    double ce = moonCalcElevation(e, vc);
                    if (ae * ce <= 0.0) {
                        be = ce;
                        b = c;
                    } else {
                        ae = ce;
                        a = c;
                    }
                }

                if (nType == 1) {
                    rise.setDayTime((c + nDaylightSavingShift) / 24.0);
                } else {
                    set.setDayTime((c + nDaylightSavingShift) / 24.0);
                }
                nFound |= nType;
                if (nFound == 0x3) {
                    break;
                }
            }
            prevElev = elev;
            ut += 1.0;
        }
        return new MoonTimes(rise, set);
    }

    public static NextMoonRasi getNextMoonRasi(EarthData ed, GcGregorianDate startDate) {
        GcGregorianDate nextDate = new GcGregorianDate(startDate);
        double phi = 30.0;
        double jday = startDate.getJulianComplete();
        MoonData moon = new MoonData();
        GcGregorianDate d = new GcGregorianDate(startDate);
        double ayanamsa = GcAyanamsha.getAyanamsa(jday);
        double scanStep = 0.5;
        int prevNaks;
        int newNaks = -1;

        double xj;
        GcGregorianDate xd = new GcGregorianDate();

        double l1;
        double l2;

        moon.calculate(jday, ed);
        l1 = GcMath.putIn360(moon.longitudeDeg - ayanamsa);
        prevNaks = (int) GcMath.floor(l1 / phi);

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

            moon.calculate(jday, ed);
            l2 = GcMath.putIn360(moon.longitudeDeg - ayanamsa);
            newNaks = (int) GcMath.floor(l2 / phi);
            if (prevNaks != newNaks) {
                jday = xj;
                d.set(xd);
                scanStep *= 0.5;
                counter += 1;
                continue;
            } else {
                l1 = l2;
            }
        }
        nextDate.set(d);
        return new NextMoonRasi(newNaks, nextDate);
    }

    public static GcGregorianDate getNextRise(EarthData e, GcGregorianDate vc, boolean bRise) {
        double hour = 1 / 24.0;

        GcGregorianDate track = new GcGregorianDate(vc);
        double[] normalized = GcGregorianDate.normalizedValues(track, false);
        track.year = (int) normalized[0];
        track.month = (int) normalized[1];
        track.day = (int) normalized[2];
        track.shour = normalized[3];

        double[] h = new double[3];

        // initialisation of the first ELEVATION value
        h[0] = moonCalcElevation(e, track);
        track.shour += hour;
        h[1] = moonCalcElevation(e, track);
        track.shour += hour;
        h[2] = moonCalcElevation(e, track);

        for (int c = 0; c < 24; c++) {
            boolean hasChange;
            if (bRise) {
                hasChange = h[1] < 0.0 && h[2] > 0.0;
            } else {
                hasChange = h[1] > 0.0 && h[2] < 0.0;
            }
            if (hasChange) {
                double a = (h[2] - h[1]) / hour;
                double b = h[2] - a * track.shour;
                track.shour = -b / a;
                double[] nv = GcGregorianDate.normalizedValues(track, false);
                track.year = (int) nv[0];
                track.month = (int) nv[1];
                track.day = (int) nv[2];
                track.shour = nv[3];
                return track;
            }

            h[0] = h[1];
            h[1] = h[2];
            track.shour += hour;
            h[2] = moonCalcElevation(e, track);
        }

        return track;
    }

    public static double moonDistance(double jdate) {
        int[][] argLr = {
            {0, 0, 1, 0}, {2, 0, -1, 0}, {2, 0, 0, 0}, {0, 0, 2, 0}, {0, 1, 0, 0},
            {0, 0, 0, 2}, {2, 0, -2, 0}, {2, -1, -1, 0}, {2, 0, 1, 0}, {2, -1, 0, 0},
            {0, 1, -1, 0}, {1, 0, 0, 0}, {0, 1, 1, 0}, {2, 0, 0, -2}, {0, 0, 1, 2},
            {0, 0, 1, -2}, {4, 0, -1, 0}, {0, 0, 3, 0}, {4, 0, -2, 0}, {2, 1, -1, 0},
            {2, 1, 0, 0}, {1, 0, -1, 0}, {1, 1, 0, 0}, {2, -1, 1, 0}, {2, 0, 2, 0},
            {4, 0, 0, 0}, {2, 0, -3, 0}, {0, 1, -2, 0}, {2, 0, -1, 2}, {2, -1, -2, 0},
            {1, 0, 1, 0}, {2, -2, 0, 0}, {0, 1, 2, 0}, {0, 2, 0, 0}, {2, -2, -1, 0},
            {2, 0, 1, -2}, {2, 0, 0, 2}, {4, -1, -1, 0}, {0, 0, 2, 2}, {3, 0, -1, 0},
            {2, 1, 1, 0}, {4, -1, -2, 0}, {0, 2, -1, 0}, {2, 2, -1, 0}, {2, 1, -2, 0},
            {2, -1, 0, -2}, {4, 0, 1, 0}, {0, 0, 4, 0}, {4, -1, 0, 0}, {1, 0, -2, 0},
            {2, 1, 0, -2}, {0, 0, 2, -2}, {1, 1, 1, 0}, {3, 0, -2, 0}, {4, 0, -3, 0},
            {2, -1, 2, 0}, {0, 2, 1, 0}, {1, 1, -1, 0}, {2, 0, 3, 0}, {2, 0, -1, -2}
        };

        int[] sigmaR = {
            -20905355, -3699111, -2955968, -569925, 48888, -3149, 246158,
            -152138, -170733, -204586, -129620, 108743, 104755, 10321,
            0, 79661, -34782, -23210, -21636, 24208, 30824,
            -8379, -16675, -12831, -10445, -11650, 14403, -7003,
            0, 10056, 6322, -9884, 5751, 0, -4950,
            4130, 0, -3958, 0, 3258, 2616, -1897,
            -2117, 2354, 0, 0, -1423, -1117, -1571,
            -1739, 0, -4421, 0, 0, 0, 0,
            1165, 0, 0, 8752
        };

        double t = (jdate - 2451545.0) / 36525.0;

        // mean elongation of the moon
        double d = 297.8502042 + (445267.1115168 + (-0.0016300 + (1.0 / 545868 - 1.0 / 113065000 * t) * t) * t) * t;

        // mean anomaly of the sun
        double m = 357.5291092 + (35999.0502909 + (-0.0001536 + 1.0 / 24490000 * t) * t) * t;

        // mean anomaly of the moon
        double ms = 134.9634114 + (477198.8676313 + (0.0089970 + (1.0 / 69699 - 1.0 / 1471200 * t) * t) * t) * t;

        // argument of the longitude of the moon
        double f = 93.2720993 + (483202.0175273 + (-0.0034029 + (-1.0 / 3526000 + 1.0 / 863310000 * t) * t) * t) * t;

        // correction term due to excentricity of the earth orbit
        double e = 1.0 + (-0.002516 - 0.0000074 * t) * t;

        double sr = 0;
        for (int i = 0; i < 60; i++) {
            double temp = sigmaR[i] * GcMath.cosDeg(argLr[i][0] * d + argLr[i][1] * m + argLr[i][2] * ms + argLr[i][3] * f);
            if (Math.abs(argLr[i][1]) == 1) {
                temp = temp * e;
            }
            if (Math.abs(argLr[i][1]) == 2) {
                temp = temp * e * e;
            }
            sr = sr + temp;
        }

        return 385000.56 + sr / 1000;
    }
}
