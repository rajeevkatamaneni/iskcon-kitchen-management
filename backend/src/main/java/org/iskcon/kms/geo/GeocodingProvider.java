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
	 * The same lookup, plus the geocoder's own rendering of what it decided the place was (T-042).
	 *
	 * <p><strong>Why this exists at all.</strong> A caller that has to show a person the answer needs
	 * something a person can recognise. Coordinates are not that: {@code 12.905125} is obvious to
	 * nobody, so an operator asked to confirm a pair of them will confirm anything. "Mysuru,
	 * Karnataka" when they meant Mysore Road, Bengaluru is obvious to anybody. That difference is the
	 * whole of what T-042 needed and the reason this sits beside {@link #locate} rather than
	 * replacing it.
	 *
	 * <p><strong>Defaulted, and that is the point.</strong> Every existing implementation and every
	 * existing caller is unchanged: {@link NoGeocodingProvider} keeps answering empty, the temple
	 * search and the delivery estimate keep calling {@link #locate}, and a provider with nothing to
	 * add says nothing by not overriding this. The interface also stays a functional one, so the test
	 * stubs written as lambdas still compile.
	 *
	 * <p><strong>A description is optional even on a hit.</strong> {@link Located#resolvedAddress()}
	 * may be null from any provider, including one that usually supplies it — a service can answer
	 * with coordinates and no label, and that is a hit with nothing to show, not a miss. Callers show
	 * what they were given and never require it.
	 *
	 * <p>Implementations keep {@link #locate}'s contract exactly: empty for every failure, and never
	 * a raised exception.
	 */
	default Optional<Located> describe(String place) {
		return locate(place).map(at -> new Located(at, null));
	}

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

	/**
	 * A place that was found, and how the geocoder itself writes it.
	 *
	 * @param resolvedAddress the service's normalised rendering — Google's
	 *                        {@code formatted_address} — or null where it gave none. Never a rewrite
	 *                        of what the caller typed:
	 *                        echoing the query back would look like a confirmation while confirming
	 *                        nothing, which is worse than showing nothing at all.
	 */
	record Located(Coordinates at, String resolvedAddress) {
	}
}
