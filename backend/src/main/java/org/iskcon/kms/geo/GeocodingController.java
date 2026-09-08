package org.iskcon.kms.geo;

import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Turning a temple's address into its coordinates, at the one moment they can still be got right
 * (T-042).
 *
 * <p><strong>What this is for.</strong> Provisioning asked a human to type two free-text six-decimal
 * numbers, and that is where a wrong temple location comes from. Since T-041 the coordinates are
 * read-only after provisioning — D-17: <em>"Why would anyone want to change the Lat and Long of a
 * Temple?… A temple is a HUGE establishment"</em> — so the moment they are typed is the only moment
 * they can be corrected. Rajeev, on the same day: <em>"If you want to use the back end we have to
 * translate an address to Lat Long, go for it. That is a VERY handy feature to have."</em>
 *
 * <p><strong>Behind {@code MANAGE_TENANTS}, which is the operator alone.</strong> D-13 settled that
 * a temple's profile is the platform operator's to change and nobody else's, and this is the same
 * screen's machinery. It is also a call out to somebody else's free service, so it is not left open
 * to anybody holding a session.
 *
 * <p><strong>It lives in {@code geo} and not in {@code tenant}.</strong> Nothing here knows what a
 * temple is: it takes a string, asks the port where that is, and answers. The provisioning screen is
 * its first caller and will not be its last — a delivery address and a vendor address want the same
 * question asked.
 *
 * <p><strong>Nothing here raises and nothing here has an error code.</strong> The ports already
 * promise never to throw, and a lookup that misses is an ordinary answer that leaves the operator
 * typing the two numbers exactly as they did before. Provisioning a temple must not be blocked by a
 * map service having a bad afternoon. See {@link GeocodedAddressView} for the shape of that answer.
 *
 * <p><strong>Off in every environment today.</strong> {@code kms.geocoding.provider} defaults to
 * {@code none} and is set nowhere, so {@link NoGeocodingProvider} answers and this endpoint reports
 * that nothing came back — which is honest, and leaves the screen exactly as it was. Setting
 * {@code GEOCODING_PROVIDER=nominatim} is what switches it on; the default is left alone on purpose,
 * because it is what keeps local development and the whole test suite off the network.
 */
@RestController
@RequestMapping("/api/v1/geocode")
public class GeocodingController {

	private final GeocodingProvider geocoding;
	private final StaticMapProvider staticMaps;

	public GeocodingController(GeocodingProvider geocoding, StaticMapProvider staticMaps) {
		this.geocoding = geocoding;
		this.staticMaps = staticMaps;
	}

	/**
	 * Where that address is, with something the operator can look at and recognise.
	 *
	 * @param address what was typed into the address box. Optional rather than required so a blank
	 *                one is an empty answer instead of a 400 an operator would have to be shown: an
	 *                empty box is not a mistake, it is somebody who has not typed yet.
	 */
	@GetMapping
	@PreAuthorize("hasAuthority('MANAGE_TENANTS')")
	public GeocodedAddressView geocode(@RequestParam(required = false) String address) {
		if (address == null || address.isBlank()) {
			return GeocodedAddressView.nothing();
		}

		// describe() rather than locate(): this is the one caller that has to show its answer to a
		// person, and a person cannot check a pair of six-decimal numbers. See GeocodingProvider.
		Optional<GeocodingProvider.Located> where = geocoding.describe(address.trim());
		if (where.isEmpty()) {
			return GeocodedAddressView.nothing();
		}

		GeocodingProvider.Coordinates at = where.get().at();

		// Wide enough to show the junction and the main road rather than a rooftop — the same
		// reasoning as the delivery sheet's map, and the same DEFAULT_ZOOM behind it. The size is a
		// strip that sits above the two coordinate boxes without pushing the rest of the form off
		// the screen; the provider clamps it to what the service will actually serve.
		Optional<byte[]> png = staticMaps.configured()
				? staticMaps.map(at, 600, 300)
				: Optional.empty();

		// May be null even on a hit — a geocoder can answer with a position and no label, and the
		// default port implementation supplies none at all. That is a hit with nothing to show
		// rather than a miss, and the screen falls back to the coordinates.
		return GeocodedAddressView.at(at, where.get().resolvedAddress(), png);
	}
}
