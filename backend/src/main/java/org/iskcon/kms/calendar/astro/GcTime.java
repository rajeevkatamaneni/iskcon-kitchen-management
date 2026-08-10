package org.iskcon.kms.calendar.astro;

/**
 * Faithful port of GCTime.py.
 *
 * <p>The Python source has a bug where some methods write {@code self.mili} and
 * others {@code self.milli}. Here a single field {@code milli} is used
 * consistently.
 */
public class GcTime {

    public int hour;
    public int min;
    public int sec;
    public int milli;

    public GcTime() {
        this(0, 0, 0, 0);
    }

    public GcTime(int hour, int min, int sec, int milli) {
        this.hour = hour;
        this.min = min;
        this.sec = sec;
        this.milli = milli;
    }

    // int[]{h,m,s}
    private static int[] normalize(int h, int m, int s) {
        while (s > 59) {
            s -= 60;
            m += 1;
        }
        while (s < 0) {
            s += 60;
            m -= 1;
        }
        while (m > 59) {
            m -= 60;
            h += 1;
        }
        while (m < 0) {
            m += 60;
            h -= 1;
        }
        return new int[] {h, m, s};
    }

    public void setDayTime(double d) {
        double timeHr = d * 24;
        this.hour = (int) Math.floor(timeHr);

        timeHr -= this.hour;
        timeHr *= 60;
        this.min = (int) Math.floor(timeHr);

        timeHr -= this.min;
        timeHr *= 60;
        this.sec = (int) Math.floor(timeHr);

        timeHr -= this.sec;
        timeHr *= 1000;
        this.milli = (int) Math.floor(timeHr);
    }

    public void setValue(int i) {
        int[] hms = normalize(0, 0, i);
        this.hour = hms[0];
        this.min = hms[1];
        this.sec = hms[2];
        this.milli = (int) (i - Math.floor(i));
    }

    public void set(GcTime d) {
        set(d, 0);
    }

    public void set(GcTime d, int offset) {
        this.hour = d.hour;
        this.min = d.min;
        this.sec = d.sec;
        this.milli = d.milli;
        if (offset != 0) {
            addMinutes(offset);
        }
    }

    public boolean isGreaterThan(GcTime dt) {
        return seconds() > dt.seconds();
    }

    public boolean isLessThan(GcTime dt) {
        return seconds() < dt.seconds();
    }

    public boolean isGreaterOrEqualThan(GcTime dt) {
        return seconds() >= dt.seconds();
    }

    public boolean isLessOrEqualThan(GcTime dt) {
        return seconds() <= dt.seconds();
    }

    public double seconds() {
        return this.hour * 3600 + this.min * 60 + this.sec + this.milli / 1000.0;
    }

    public void addMinutes(double mn) {
        int[] hms = normalize(this.hour, this.min, this.sec + (int) (mn * 60));
        this.hour = hms[0];
        this.min = hms[1];
        this.sec = hms[2];
    }

    public void addSeconds(double sc) {
        int[] hms = normalize(this.hour, this.min, this.sec + (int) sc);
        this.hour = hms[0];
        this.min = hms[1];
        this.sec = hms[2];
    }

    public double getDayTime() {
        return ((this.hour * 60.0 + this.min) * 60.0 + this.sec) / 86400.0;
    }

    /**
     * Conversion of time from DEGREE format to H:M:S:MS format.
     *
     * @param timeDeg input time in range 0 deg to 360 deg where 0 deg = 0:00 AM
     *                and 360 deg = 12:00 PM
     */
    public void setDegTime(double timeDeg) {
        timeDeg = GcMath.putIn360(timeDeg);

        double timeHr = timeDeg / 360 * 24;
        this.hour = (int) Math.floor(timeHr);

        timeHr -= this.hour;
        timeHr *= 60;
        this.min = (int) Math.floor(timeHr);

        timeHr -= this.min;
        timeHr *= 60;
        this.sec = (int) Math.floor(timeHr);

        timeHr -= this.sec;
        timeHr *= 1000;
        this.milli = (int) Math.floor(timeHr);
    }

    public String toLongTimeString() {
        return String.format("%02d:%02d:%02d", this.hour, this.min, this.sec);
    }

    @Override
    public String toString() {
        return String.format("%02d:%02d:%02d.%03d", this.hour, this.min, this.sec,
                ((this.milli % 1000) + 1000) % 1000);
    }
}
