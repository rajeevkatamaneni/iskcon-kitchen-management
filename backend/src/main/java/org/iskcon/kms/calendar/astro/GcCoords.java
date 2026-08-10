package org.iskcon.kms.calendar.astro;

/**
 * Coordinate holder classes, port of GCCoords.py.
 */
public final class GcCoords {

    private GcCoords() {
    }

    public static final class HorizontalCoords {
        public double azimut = 0.0;
        public double elevation = 0.0;
    }

    public static final class EquatorialCoords {
        public double rightAscension = 0.0;
        public double declination = 0.0;
    }

    public static final class EclipticalCoords {
        public double latitude = 0.0;
        public double longitude = 0.0;
        public double distance = 0.0;
    }
}
