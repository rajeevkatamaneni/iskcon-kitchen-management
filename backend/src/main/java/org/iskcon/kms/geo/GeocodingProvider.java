package org.iskcon.kms.geo;

import java.util.Optional;

/**
 * Turns a place a person typed — "Jayanagar", "Mysuru", "Hare Krishna Hill" — into coordinates, so
 * temples can be offered by how far away they are rather than by how the address happens to be
 * spelled.
 *
 * <p>A port, like the translation and payment providers: the registration screen should not care
 * which map service answers, and the test suite should not need one at all.
 */
public interface GeocodingProvider {

	/** Where that place is, or empty when it cannot be found — which is not an error worth raising. */
	Optional<Coordinates> locate(String place);

	record Coordinates(double latitude, double longitude) {
	}
}
