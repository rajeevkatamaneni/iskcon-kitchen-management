package org.iskcon.kms.geo;

import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The default: no map service, and the delivery sheet prints the address without a picture.
 *
 * <p>This is the normal case, not a fault. A temple that has not been given a Maps key gets a
 * delivery sheet carrying a name, a number, an address and a travel allowance — everything a driver
 * actually needs — and no map. Nothing anywhere says the map is missing, because nobody was promised
 * one.
 */
@Component
@ConditionalOnProperty(name = "kms.static-map.provider", havingValue = "none", matchIfMissing = true)
public class NoStaticMapProvider implements StaticMapProvider {

	@Override
	public boolean configured() {
		return false;
	}

	@Override
	public Optional<byte[]> map(GeocodingProvider.Coordinates at, int widthPx, int heightPx) {
		return Optional.empty();
	}
}
