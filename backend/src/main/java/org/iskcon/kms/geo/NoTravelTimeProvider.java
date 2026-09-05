package org.iskcon.kms.geo;

import java.time.Instant;
import java.util.Optional;
import org.iskcon.kms.geo.GeocodingProvider.Coordinates;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The default: no routing service at all (E4-S16 D3). Local development and the test suite stay
 * hermetic — the whole suite passes with no network and no credentials — and a temple that has not
 * been given a map service plans a delivery in exactly the same number of clicks as one that has.
 *
 * <p>What the screen shows instead is one quiet sentence saying the estimate is unavailable. Not a
 * red error, not a spinner that never stops: a map service the temple does not have is not a failure
 * of the temple's, and the meal plan is the thing that matters.
 */
@Component
@ConditionalOnProperty(name = "kms.travel-time.provider", havingValue = "none", matchIfMissing = true)
public class NoTravelTimeProvider implements TravelTimeProvider {

	@Override
	public Optional<TravelTime> drive(Coordinates origin, Coordinates destination, Instant departAt) {
		return Optional.empty();
	}

	@Override
	public boolean configured() {
		return false;
	}
}
