package org.iskcon.kms.geo;

import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The default: no map service at all. Local development and the test suite stay hermetic, and the
 * temple search falls back to matching the text against temple names and addresses.
 */
@Component
@ConditionalOnProperty(name = "kms.geocoding.provider", havingValue = "none", matchIfMissing = true)
public class NoGeocodingProvider implements GeocodingProvider {

	@Override
	public Optional<Coordinates> locate(String place) {
		return Optional.empty();
	}
}
