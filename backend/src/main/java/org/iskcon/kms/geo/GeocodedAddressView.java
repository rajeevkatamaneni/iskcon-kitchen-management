package org.iskcon.kms.geo;

import java.util.Base64;
import java.util.Optional;

/**
 * What came back when an address was looked up, for the operator to confirm before it is kept
 * (T-042, D-17).
 *
 * <p><strong>Not finding an address is an answer, not a failure.</strong> There is no {@code KMS-}
 * code here and none is wanted: provisioning a temple must never be blocked by a map service, so
 * every unhappy path — nothing matched, the service is down, no service is configured at all —
 * lands on {@link #nothing()} with a 200 beside it, and the operator carries on typing the
 * coordinates by hand. {@code DELIVERY_ADDRESS_NOT_FOUND} (KMS-400078) is the precedent: a lookup
 * that misses costs a convenience and nothing else.
 *
 * <p><strong>The picture travels inside the JSON.</strong> {@code mapDataUri} is a whole PNG as
 * {@code data:image/png;base64,…}, exactly as {@code JobCardService.mapFor} inlines the delivery
 * sheet's map. It is not a second endpoint returning image bytes, because an {@code <img src>}
 * cannot carry a bearer token and this whole surface sits behind {@code MANAGE_TENANTS}.
 *
 * <p><strong>Why three ways of showing the same answer.</strong> The operator has to be able to see
 * that the lookup found the wrong building, and how that is shown depends on what the deployment
 * has:
 *
 * <ul>
 *   <li>a static-map key — a pin on a map, which is the best of the three and is obvious at a
 *       glance;
 *   <li>no key — the geocoder's own normalised rendering of what it matched, which a person reads
 *       as easily as a pin ("Mysuru, Karnataka" when they meant Mysore Road, Bengaluru);
 *   <li>neither — the coordinates alone, which is weak, but is still better than the numbers being
 *       typed by a human in the first place.
 * </ul>
 *
 * <p>Each is an upgrade on the one below it and none of them is a different flow: the screen asks
 * the same question — <em>is this the right place?</em> — and turning a key on changes what is
 * shown under it and nothing else.
 */
public record GeocodedAddressView(
		boolean found,
		Double latitude,
		Double longitude,
		String resolvedAddress,
		String mapDataUri) {

	/**
	 * Nothing came back — and deliberately without saying which of the several reasons it was.
	 *
	 * <p>The caller cannot tell "the geocoder looked and missed" from "this deployment has no
	 * geocoder", and neither can the screen. {@link GeocodingProvider#configured()} exists precisely
	 * to keep the second from being reported as the first, so the words on the screen say only what
	 * is true of both — no coordinates came back — rather than telling an operator an address could
	 * not be found when nobody ever looked for it.
	 */
	public static GeocodedAddressView nothing() {
		return new GeocodedAddressView(false, null, null, null, null);
	}

	/**
	 * A hit, with whatever confirmation this deployment can draw.
	 *
	 * @param resolvedAddress the geocoder's normalised rendering, or null where the provider does not
	 *                        supply one — which is every provider but Nominatim, since the port
	 *                        defaults {@link GeocodingProvider#describe} to a position with no label.
	 * @param png             the map, or empty. Encoded here rather than by the caller so the one
	 *                        place that knows this field is a data URI is the record that declares
	 *                        it.
	 */
	public static GeocodedAddressView at(
			GeocodingProvider.Coordinates where, String resolvedAddress, Optional<byte[]> png) {
		return new GeocodedAddressView(
				true,
				where.latitude(),
				where.longitude(),
				resolvedAddress == null || resolvedAddress.isBlank() ? null : resolvedAddress,
				png.filter(bytes -> bytes.length > 0)
						.map(bytes -> "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes))
						.orElse(null));
	}
}
