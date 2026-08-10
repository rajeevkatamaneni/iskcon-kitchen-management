package org.iskcon.kms.calendar.astro;

/**
 * Faithful port of GCSunData.py (the SUNDATA class plus the module-level
 * functions, which here are static methods).
 */
public class SunData {

    public double lengthDeg = 0.0;
    public double arunodayaDeg = 0.0;
    public double sunriseDeg = 0.0;
    public double sunsetDeg = 0.0;

    public double declinationDeg = 0.0;
    public double longitudeDeg = 0.0;
    public double longitudeSetDeg = 0.0;
    public double longitudeArunDeg = 0.0;
    public double rightAscDeg = 0.0;

    // time of arunodaya - 96 mins before sunrise
    public GcTime arunodaya = new GcTime();
    // time of sunrise
    public GcTime rise = new GcTime();
    // time of noon
    public GcTime noon = new GcTime();
    // time of sunset
    public GcTime set = new GcTime();
    // length of the day
    public GcTime length = new GcTime();

    public static double getSunLongitude(GcGregorianDate vct) {
        double dg = GcMath.PI / 180;

        // mean ecliptic longitude of the sun
        double mel = sunGetMeanLong(vct.year, vct.month, vct.day)
                + (360.0 / 365.25) * (vct.shour - 0.5 - vct.tzone / 24.0);

        // ecliptic longitude of perigee
        double elp = sunGetPerigee(vct.year, vct.month, vct.day);

        // mean anomaly of the sun
        double mas = mel - elp;

        // ecliptic longitude of the sun
        return mel + 1.915 * Math.sin(mas * dg) + 0.02 * Math.sin(2 * dg * mas);
    }

    // find mean ecliptic longitude of the sun for your chosen day
    public static double sunGetMeanLong(int year, int month, int day) {
        double[] sunLong = {
            339.226, 009.781, 039.351, 069.906, 099.475, 130.030, 160.585, 190.155, 220.710, 250.279, 280.834, 311.390,
            340.212, 010.767, 040.337, 070.892, 100.461, 131.016, 161.571, 191.141, 221.696, 251.265, 281.820, 312.375,
            341.198, 011.753, 041.322, 071.877, 101.447, 132.002, 162.557, 192.126, 222.681, 252.251, 282.806, 313.361,
            342.183, 012.738, 042.308, 072.863, 102.432, 132.987, 163.542, 193.112, 223.667, 253.236, 283.791, 314.346,
            343.169, 013.724, 043.293, 073.849, 103.418, 133.973, 164.528, 194.098, 224.653, 254.222, 284.777, 315.332,
            344.155, 014.710, 044.279, 074.834, 104.404, 134.959, 165.514, 195.083, 225.638, 255.208, 285.763, 316.318,
            345.140, 015.695, 045.265, 075.820, 105.389, 135.944, 166.499, 196.069, 226.624, 256.193, 286.748, 317.303,
            346.126, 016.681, 046.250, 076.805, 106.375, 136.930, 167.485, 197.054, 227.610, 257.179, 287.734, 318.289,
            347.112, 017.667, 047.236, 077.791, 107.361, 137.916, 168.471, 198.040, 228.595, 258.165, 288.720, 319.275,
            348.097, 018.652, 048.222, 078.777, 108.346, 138.901, 169.456, 199.026, 229.581, 259.150, 289.705, 320.260,
            349.083, 019.638, 049.207, 079.762, 109.332, 139.887, 170.442, 200.011, 230.566, 260.136, 290.691, 321.246,
            350.068, 020.624, 050.193, 080.748, 110.317, 140.873, 171.428, 200.997, 231.552, 261.122, 291.677, 322.232,
            351.054, 021.609, 051.179, 081.734, 111.303, 141.858, 172.413, 201.983, 232.538, 262.107, 292.662, 323.217,
            352.040, 022.595, 052.164, 082.719, 112.289, 142.844, 173.399, 202.968, 233.523, 263.093, 293.648, 324.203,
            353.025, 023.581, 053.150, 083.705, 113.274, 143.829, 174.385, 203.954, 234.509, 264.078, 294.634, 325.189,
            354.011, 024.566, 054.136, 084.691, 114.260, 144.815, 175.370, 204.940, 235.495, 265.064, 295.619, 326.174,
            354.997, 025.552, 055.121, 085.676, 115.246, 145.801, 176.356, 205.925, 236.480, 266.050, 296.605, 327.160,
            355.982, 026.537, 056.107, 086.662, 116.231, 146.786, 177.341, 206.911, 237.466, 267.035, 297.590, 328.146,
            356.968, 027.523, 057.093, 087.648, 117.217, 147.772, 178.327, 207.897, 238.452, 268.021, 298.576, 329.131,
            357.954, 028.509, 058.078, 088.633, 118.203, 148.758, 179.313, 208.882, 239.437, 269.007, 299.562, 330.117,
            358.939, 029.494, 059.064, 089.619, 119.188, 149.743, 180.298, 209.868, 240.423, 269.992, 300.547, 331.102,
            359.925, 030.480, 060.049, 090.605, 120.174, 150.729, 181.284, 210.854, 241.409, 270.978, 301.533, 332.088,
            000.911, 031.466, 061.035, 091.590, 121.160, 151.715, 182.270, 211.839, 242.394, 271.964, 302.519, 333.074,
            001.896, 032.451, 062.021, 092.576, 122.145, 152.700, 183.255, 212.825, 243.380, 272.949, 303.504, 334.059,
            002.882, 033.437, 063.006, 093.561, 123.131, 153.686, 184.241, 213.810, 244.366, 273.935, 304.490, 335.045,
            003.868, 034.423, 063.992, 094.547, 124.117, 154.672, 185.227, 214.796, 245.351, 274.921, 305.476, 336.031,
            004.853, 035.408, 064.978, 095.533, 125.102, 155.657, 186.212, 215.782, 246.337, 275.906, 306.461, 337.016,
            005.839, 036.394, 065.963, 096.518, 126.088, 156.643, 187.198, 216.767, 247.322, 276.892, 307.447, 338.002,
            006.824, 037.380, 066.949, 097.504, 127.073, 157.629, 188.184, 217.753, 248.308, 277.878, 308.433, 338.988,
            007.810, 038.365, 067.935, 098.490, 128.059, 158.614, 189.169, 218.739, 249.294, 278.863, 309.418, 339.100,
            008.796, 038.365, 068.920, 098.490, 129.045, 159.600, 189.169, 219.724, 249.294, 279.849, 310.404, 339.226,
        };

        double[] sun1Col = {-001.157, -000.386, 000.386, 001.157};
        double[] sun1Row = {-001.070, 002.015, 005.101, 008.186, 011.271, 014.356, 017.441, 020.526, 023.611, 026.697};
        double[] sun2Col = {000.322, 000.107, -000.107, -000.322};
        double[] sun2Row = {-000.577, -000.449, -000.320, -000.192, -000.064, 000.064, 000.192, 000.320, 000.449, 000.577};
        double[] sun3Row = {-000.370, -000.339, -000.309, -000.278, -000.247, -000.216, -000.185, -000.154, -000.123, -000.093, -000.062, -000.031, +000.000, +000.031, +000.062, +000.093, +000.123, +000.154, +000.185, +000.216, +000.247, +000.278, +000.309, +000.339, +000.370};
        double[] sun3Col = {+000.358, +000.119, -000.119, -000.358};

        double mel;

        if ((month > 12) || (month < 1) || (day < 1) || (day > 31)) {
            return -1.0;
        }
        mel = sunLong[(day - 1) * 12 + (month + 9) % 12];

        int y;
        int yy;

        if (month < 3) {
            year -= 1;
        }
        y = year / 100;
        yy = year % 100;

        if (y <= 15) {
            mel += sun1Col[y % 4] + sun1Row[y / 4];
        } else if (y < 40) {
            mel += sun2Col[y % 4] + sun2Row[y / 4];
        }

        mel += sun3Col[yy % 4] + sun3Row[yy / 4];

        return mel;
    }

    // finds ecliptic longitude of perigee of the sun for the mean summer
    // solstice of your chosen year (and effectively for the entire year)
    public static double sunGetPerigee(int year, int month, int day) {
        double[] sun4Row = {251.97, 258.85, 265.73, 272.61, 279.49, 286.37, 293.25, 300.14, 307.02, 313.90};
        double[] sun4Col = {-002.58, -000.86, 000.86, 002.58};
        double[] sun5Row = {-000.83, -000.76, -000.69, -000.62, -000.55, -000.48, -000.41, -000.34, -000.28, -000.21, -000.14, -000.07, +000.00, +000.07, +000.14, +000.21, +000.28, +000.34, +000.41, +000.48, +000.55, +000.62, +000.69, +000.76, +000.83};
        double[] sun5Col = {-000.03, -000.01, 000.01, +000.03};

        double per;

        if ((month > 12) || (month < 1) || (day < 1) || (day > 31)) {
            return -1.0;
        }

        if (month < 3) {
            year -= 1;
        }
        int y = year / 100;
        int yy = year % 100;

        per = sun4Row[y / 4] + sun4Col[y % 4];
        per += sun5Row[yy / 4] + sun5Col[yy % 4];

        return per;
    }

    /**
     * Port of CalculateKala.
     *
     * @return {@code double[]{r1, r2}}
     */
    public static double[] calculateKala(double sunRise, double sunSet, int dayWeek, KalaType type) {
        double r1 = 0.0;
        double r2 = 0.0;

        if (type == KalaType.KT_RAHU_KALAM) {
            int[] a = {7, 1, 6, 4, 5, 3, 2};
            double period = (sunSet - sunRise) / 8.0;
            r1 = sunRise + a[dayWeek] * period;
            r2 = r1 + period;
        } else if (type == KalaType.KT_YAMA_GHANTI) {
            int[] a = {4, 3, 2, 1, 0, 6, 5};
            double period = (sunSet - sunRise) / 8.0;
            r1 = sunRise + a[dayWeek] * period;
            r2 = r1 + period;
        } else if (type == KalaType.KT_GULI_KALAM) {
            int[] a = {6, 5, 4, 3, 2, 1, 0};
            double period = (sunSet - sunRise) / 8.0;
            r1 = sunRise + a[dayWeek] * period;
            r2 = r1 + period;
        } else if (type == KalaType.KT_ABHIJIT) {
            double period = (sunSet - sunRise) / 15.0;
            r1 = sunRise + 7 * period;
            r2 = r1 + period;
            if (dayWeek == 3) {
                r1 = r2 = -1;
            }
        }
        return new double[] {r1, r2};
    }

    public void sunPosition(GcGregorianDate vct, EarthData ed, double dayHours) {
        double dg = GcMath.PI / 180;
        double rad = 180 / GcMath.PI;

        double dLatitude = ed.latitudeDeg;
        double dLongitude = ed.longitudeDeg;

        // mean ecliptic longitude of the sun
        double mel = sunGetMeanLong(vct.year, vct.month, vct.day) + (360.0 / 365.25) * dayHours / 360.0;

        // ecliptic longitude of perigee
        double elp = sunGetPerigee(vct.year, vct.month, vct.day);

        // mean anomaly of the sun
        double mas = mel - elp;

        // ecliptic longitude of the sun
        double els;
        this.longitudeDeg = els = mel + 1.915 * Math.sin(mas * dg) + 0.02 * Math.sin(2 * dg * mas);

        // declination of the sun
        this.declinationDeg = rad * Math.asin(0.39777 * Math.sin(els * dg));

        // right ascension of the sun
        this.rightAscDeg = els - rad * Math.atan2(Math.sin(2 * els * dg), 23.2377 + Math.cos(2 * dg * els));

        // equation of time
        double eqt = this.rightAscDeg - mel;

        // definition of event
        // civil twilight                    eventdef = 0.10453;
        // nautical twilight                 eventdef = 0.20791;
        // astronomical twilight             eventdef = 0.30902;
        // center of the sun on the horizont eventdef = 0.01454;
        double eventdef = 0.01454;

        eventdef = (eventdef / Math.cos(dLatitude * dg)) / Math.cos(this.declinationDeg * dg);

        double x = Math.tan(dLatitude * dg) * Math.tan(this.declinationDeg * dg) + eventdef;

        // initial values for the case that no rise no set for that day
        this.sunriseDeg = this.sunsetDeg = -360.0;

        if ((x >= -1.0) && (x <= 1.0)) {
            // time of sunrise
            this.sunriseDeg = 90.0 - dLongitude - rad * Math.asin(x) + eqt;
            // time of sunset
            this.sunsetDeg = 270.0 - dLongitude + rad * Math.asin(x) + eqt;
        }
    }

    /**
     * Return values are in {@code arunodaya}, {@code rise}, {@code set},
     * {@code noon}, {@code length}. If values are less than zero, that means no
     * sunrise/no sunset in that day.
     */
    public void sunCalc(GcGregorianDate vct, EarthData earth) {
        SunData sRise = new SunData();
        SunData sSet = new SunData();

        sRise.sunriseDeg = 180;
        sSet.sunriseDeg = 180;

        for (int i = 0; i < 3; i++) {
            sRise.sunPosition(vct, earth, sRise.sunriseDeg - 180);
            sSet.sunPosition(vct, earth, sSet.sunsetDeg - 180);
        }

        // calculate times
        this.longitudeArunDeg = sRise.longitudeDeg - (24.0 / 365.25);
        this.longitudeDeg = sRise.longitudeDeg;
        this.longitudeSetDeg = sSet.longitudeDeg;

        this.arunodayaDeg = sRise.sunriseDeg - 24.0;
        this.sunriseDeg = sRise.sunriseDeg;
        this.sunsetDeg = sSet.sunsetDeg;
        this.lengthDeg = sSet.sunsetDeg - sRise.sunriseDeg;

        // arunodaya is 96 min before sunrise
        // sunrise_deg is from range 0-360 so 96min=24deg
        this.arunodaya.setDegTime(this.arunodayaDeg + earth.tzone * 15.0);
        // sunrise
        this.rise.setDegTime(this.sunriseDeg + earth.tzone * 15.0);
        // noon
        this.noon.setDegTime((this.sunsetDeg + this.sunriseDeg) / 2 + earth.tzone * 15.0);
        // sunset
        this.set.setDegTime(this.sunsetDeg + earth.tzone * 15.0);
        // length
        this.length.setDegTime(this.lengthDeg);
    }
}
