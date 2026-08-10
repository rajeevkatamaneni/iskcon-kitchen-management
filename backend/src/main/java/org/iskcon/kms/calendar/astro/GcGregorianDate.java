package org.iskcon.kms.calendar.astro;

/**
 * Faithful port of GCGregorianDate.py.
 *
 * <p>{@code shour} is a fraction of a day (0.0 .. 1.0); {@code tzone} is the
 * timezone offset in hours.
 */
public class GcGregorianDate {

    public int year;
    public int month;
    public int day;
    public double shour;
    public double tzone;
    public int dayOfWeek;

    public GcGregorianDate() {
        this.year = 2000;
        this.month = 1;
        this.day = 1;
        this.shour = 0.5;
        this.tzone = 0.0;
        initWeekDay();
    }

    public GcGregorianDate(int year, int month, int day, double shour) {
        this(year, month, day, shour, 0.0);
    }

    public GcGregorianDate(int year, int month, int day, double shour, double tzone) {
        this.year = year;
        this.month = month;
        this.day = day;
        this.shour = shour;
        this.tzone = tzone;
        initWeekDay();
    }

    /** Copy constructor (mirror of GCGregorianDate(date=other)). */
    public GcGregorianDate(GcGregorianDate other) {
        set(other);
    }

    public GcGregorianDate copy() {
        return new GcGregorianDate(this);
    }

    public static int getMonthMaxDays(int year, int month) {
        int[] mMonths = {0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        if (isLeapYear(year) && month == 2) {
            return 29;
        }
        return mMonths[month];
    }

    public static boolean isLeapYear(int year) {
        if ((year % 4) != 0) {
            return false;
        }
        if (year % 100 == 0 && year % 400 != 0) {
            return false;
        }
        return true;
    }

    /**
     * Mirror of NormalizedValues(date, tzone).
     *
     * @return {@code double[]{year, month, day, shour}}
     */
    public static double[] normalizedValues(GcGregorianDate date, boolean useTzone) {
        int d1 = date.day;
        int m1 = date.month;
        int y1 = date.year;
        double h1 = date.shour;
        if (useTzone) {
            h1 += date.tzone / 24.0;
        }

        if (h1 < 0.0) {
            d1 -= 1;
            h1 += 1.0;
        } else if (h1 >= 1.0) {
            h1 -= 1.0;
            d1 += 1;
        }

        if (d1 < 1) {
            m1 -= 1;
            if (m1 < 1) {
                m1 = 12;
                y1 -= 1;
            }
            d1 = getMonthMaxDays(y1, m1);
        } else if (d1 > getMonthMaxDays(y1, m1)) {
            m1 += 1;
            if (m1 > 12) {
                m1 = 1;
                y1 += 1;
            }
            d1 = 1;
        }
        return new double[] {y1, m1, d1, h1};
    }

    public static int calculateJulianDay(int year, int month, int day) {
        int yy = year - (int) ((12 - month) / 10);
        int mm = month + 9;

        if (mm >= 12) {
            mm -= 12;
        }

        int k1 = (int) Math.floor(365.25 * (yy + 4712));
        int k2 = (int) Math.floor(30.6 * mm + 0.5);
        int k3 = (int) Math.floor(Math.floor((yy / 100.0) + 49) * .75) - 38;
        int j = k1 + k2 + day + 59;
        if (j > 2299160) {
            j -= k3;
        }
        return j;
    }

    public int getJulianInteger() {
        return calculateJulianDay(this.year, this.month, this.day);
    }

    public double getJulian() {
        return getJulianInteger() * 1.0;
    }

    public double getJulianDetailed() {
        return getJulian() - 0.5 + this.shour;
    }

    public double getJulianComplete() {
        return getJulian() - 0.5 + this.shour - this.tzone / 24.0;
    }

    public void previousDay() {
        this.day -= 1;
        if (this.day < 1) {
            this.month -= 1;
            if (this.month < 1) {
                this.month = 12;
                this.year -= 1;
            }
            this.day = getMonthMaxDays(this.year, this.month);
        }
        this.dayOfWeek = (this.dayOfWeek + 6) % 7;
    }

    public void nextDay() {
        this.day += 1;
        if (this.day > getMonthMaxDays(this.year, this.month)) {
            this.month += 1;
            if (this.month > 12) {
                this.month = 1;
                this.year += 1;
            }
            this.day = 1;
        }
        this.dayOfWeek = (this.dayOfWeek + 1) % 7;
    }

    public void addDays(int n) {
        if (n > 0) {
            for (int i = 0; i < n; i++) {
                nextDay();
            }
        } else if (n < 0) {
            for (int i = 0; i < -n; i++) {
                previousDay();
            }
        }
    }

    public void initWeekDay() {
        this.dayOfWeek = (getJulianInteger() + 1) % 7;
    }

    public void setFromJulian(double jd) {
        double z = Math.floor(jd + 0.5);
        double f = (jd + 0.5) - z;
        double a;
        if (z < 2299161.0) {
            a = z;
        } else {
            double alpha = Math.floor((z - 1867216.25) / 36524.25);
            a = z + 1.0 + alpha - Math.floor(alpha / 4.0);
        }

        double b = a + 1524;
        double c = Math.floor((b - 122.1) / 365.25);
        double dd = Math.floor(365.25 * c);
        double e = Math.floor((b - dd) / 30.6001);
        this.day = (int) Math.floor(b - dd - Math.floor(30.6001 * e) + f);
        if (e < 14) {
            this.month = (int) (e - 1);
        } else {
            this.month = (int) (e - 13);
        }
        if (this.month > 2) {
            this.year = (int) (c - 4716);
        } else {
            this.year = (int) (c - 4715);
        }
        this.tzone = 0.0;
        this.shour = GcMath.getFraction(jd + 0.5);
    }

    public void set(GcGregorianDate ta) {
        this.year = ta.year;
        this.month = ta.month;
        this.day = ta.day;
        this.shour = ta.shour;
        this.tzone = ta.tzone;
        this.dayOfWeek = ta.dayOfWeek;
    }

    public int getHour() {
        return (int) Math.floor(this.shour * 24);
    }

    public int getMinute() {
        return (int) Math.floor(GcMath.getFraction(this.shour * 24) * 60);
    }

    public int getSecond() {
        return (int) Math.floor(GcMath.getFraction(this.shour * 1440) * 60);
    }

    public int getMinuteRound() {
        return (int) Math.floor(GcMath.getFraction(this.shour * 24) * 60 + 0.5);
    }

    public void subtractDays(int n) {
        addDays(-n);
    }

    /** Mirror of NormalizeHours(): carry the shour fraction into whole days. */
    public void normalizeHours() {
        while (this.shour < 0.0) {
            this.shour += 1.0;
            previousDay();
        }
        while (this.shour > 1.0) {
            this.shour -= 1.0;
            nextDay();
        }
    }

    /** Mirror of NormalizeValues(): normalise shour/day/month/year (no tzone). */
    public void normalizeValues() {
        double[] v = normalizedValues(this, false);
        this.year = (int) v[0];
        this.month = (int) v[1];
        this.day = (int) v[2];
        this.shour = v[3];
    }

    /** Mirror of IsBeforeThis(date). */
    public boolean isBeforeThis(GcGregorianDate date) {
        if (this.year > date.year) {
            return false;
        }
        if (this.year < date.year) {
            return true;
        }
        if (this.month > date.month) {
            return false;
        }
        if (this.month < date.month) {
            return true;
        }
        return this.day < date.day;
    }

    /** Mirror of CompareYMD(v). */
    public int compareYMD(GcGregorianDate v) {
        if (v.year < this.year) {
            return (this.year - v.year) * 365;
        }
        if (v.year > this.year) {
            return (this.year - v.year) * 365;
        }
        if (v.month < this.month) {
            return (this.month - v.month) * 31;
        }
        if (v.month > this.month) {
            return (this.month - v.month) * 31;
        }
        return this.day - v.day;
    }
}
