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

	/**
	 * Whether this deployment has a map service at all.
	 *
	 * <p>An empty answer means two different things and the caller sometimes has to tell them apart:
	 * a service that looked and found nothing is worth reporting to whoever typed the address
	 * (KMS-400078, E4-S16) — they can fix it. No service at all is not their doing and not their
	 * problem, and telling them an address could not be found when nobody looked would be a lie.
	 *
	 * <p>Defaulted true, so a provider that answers questions never has to say so.
	 */
	default boolean configured() {
		return true;
	}

	record Coordinates(double latitude, double longitude) {
	}
}
