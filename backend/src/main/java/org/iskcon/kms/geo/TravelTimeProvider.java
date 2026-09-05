package org.iskcon.kms.geo;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.iskcon.kms.geo.GeocodingProvider.Coordinates;

/**
 * How long the drive takes, in traffic, at a stated hour (E4-S16).
 *
 * <p>A port, exactly like {@link GeocodingProvider}: the planner should not care which map service
 * answers, and the test suite should not need one at all. And exactly like it, <strong>nothing here
 * raises.</strong> A map service that is slow, rate-limited, unbilled or down must not stand between
 * a cook and a meal plan — the answer is simply empty, and the screen says so in one quiet sentence.
 *
 * <p>The answer is a <em>range</em> and not a number on purpose. Traffic is a prediction, and a
 * prediction printed as "37 minutes" reads as a promise. Two figures read as what it is.
 */
public interface TravelTimeProvider {

	/**
	 * How long it takes to drive from {@code origin} to {@code destination}, leaving at
	 * {@code departAt}, or empty when no answer can be had.
	 *
	 * @param departAt when the vehicle leaves. Traffic-aware routing needs a real hour — the same
	 *                 road is twenty minutes at eleven in the morning and fifty at six in the
	 *                 evening — and the services that answer this insist it is in the future.
	 */
	Optional<TravelTime> drive(Coordinates origin, Coordinates destination, Instant departAt);

	/**
	 * Whether this deployment has a routing service at all. See
	 * {@link GeocodingProvider#configured()}: an empty answer from a service that tried is a
	 * different thing from no service, and the screen has different words for each.
	 */
	default boolean configured() {
		return true;
	}

	/**
	 * The optimistic and pessimistic ends of the drive.
	 *
	 * @param optimistic  the drive if the traffic is kind.
	 * @param pessimistic the drive if it is not. This is the one a leave-by time is computed from:
	 *                    arriving early with the food is an inconvenience, arriving after the guests
	 *                    have sat down is not.
	 */
	record TravelTime(Duration optimistic, Duration pessimistic) {
	}
}
