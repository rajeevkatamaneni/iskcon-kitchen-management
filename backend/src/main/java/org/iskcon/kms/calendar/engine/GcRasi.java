package org.iskcon.kms.calendar.engine;

import org.iskcon.kms.calendar.astro.GcMath;

/** Faithful port of GCRasi.py. */
final class GcRasi {

    private GcRasi() {
    }

    static int getRasi(double sunLongitude, double ayanamsa) {
        return (int) GcMath.floor(GcMath.putIn360(sunLongitude - ayanamsa) / 30.0);
    }
}
