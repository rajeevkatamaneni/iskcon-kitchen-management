package org.iskcon.kms.geo;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The delivery sheet's map, from the Maps Static API.
 *
 * <p><strong>An API key, not the service account.</strong> Unlike Routes, the Static Maps endpoint
 * has no OAuth form — it is a signed GET that takes {@code key} and nothing else. So this one does
 * need a secret, and without it the provider reports itself unconfigured and the card prints without
 * a picture rather than failing.
 *
 * <p><strong>Greyscale, to match the sheet.</strong> The card is black, white and greys by decision
 * of 2026-09-05, and most of these come off a mono laser printer where Google's default green parks
 * and brown arterials are four indistinguishable smudges. {@code style=feature:all|saturation:-100}
 * asks Google to desaturate before sending, so what arrives is what prints.
 *
 * <p><strong>Nothing here raises.</strong> A timeout, a quota, a bad key, a 404 — each is an empty
 * answer and a delivery sheet with the address in text and no map. The port's contract, and the same
 * one {@link GoogleRoutesTravelTimeProvider} keeps.
 */
@Component
@ConditionalOnProperty(name = "kms.static-map.provider", havingValue = "google")
public class GoogleStaticMapProvider implements StaticMapProvider {

	private static final Logger log = LoggerFactory.getLogger(GoogleStaticMapProvider.class);

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(3))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private final String endpoint;
	private final String apiKey;

	public GoogleStaticMapProvider(
			@Value("${kms.static-map.google.endpoint:https://maps.googleapis.com/maps/api/staticmap}")
			String endpoint,
			@Value("${kms.static-map.google.api-key:}") String apiKey) {
		this.endpoint = endpoint;
		this.apiKey = apiKey == null ? "" : apiKey.trim();
	}

	@Override
	public boolean configured() {
		return !apiKey.isEmpty();
	}

	@Override
	public Optional<byte[]> map(GeocodingProvider.Coordinates at, int widthPx, int heightPx) {
		if (at == null || !configured()) {
			return Optional.empty();
		}
		// Capped at Google's own documented maximum for the non-premium size parameter. Asking for
		// more is a 400, not a bigger picture.
		int width = Math.min(Math.max(widthPx, 100), 640);
		int height = Math.min(Math.max(heightPx, 100), 640);
		String centre = at.latitude() + "," + at.longitude();

		String url = endpoint
				+ "?center=" + enc(centre)
				+ "&zoom=" + DEFAULT_ZOOM
				+ "&size=" + width + "x" + height
				// Twice the pixels for the same ground, so it is not a blur at 300dpi.
				+ "&scale=2"
				+ "&maptype=roadmap"
				+ "&style=" + enc("feature:all|saturation:-100")
				+ "&markers=" + enc("color:0x141414|" + centre)
				+ "&key=" + enc(apiKey);

		try {
			HttpResponse<byte[]> response = http.send(
					HttpRequest.newBuilder(URI.create(url))
							.timeout(Duration.ofSeconds(6))
							.GET()
							.build(),
					HttpResponse.BodyHandlers.ofByteArray());

			if (response.statusCode() != 200) {
				// The body of a Static Maps error is a PNG with the reason written on it, which would
				// print onto the delivery sheet as a picture of an error message. Never returned.
				log.warn("The static map service answered {}; the delivery sheet prints without a map",
						response.statusCode());
				return Optional.empty();
			}
			byte[] body = response.body();
			return body == null || body.length == 0 ? Optional.empty() : Optional.of(body);

		} catch (Exception e) {
			// Broad on purpose, and interrupt is restored rather than swallowed. A map is the least
			// important thing on this page.
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			log.warn("The static map service could not be reached ({}); printing without a map",
					e.toString());
			return Optional.empty();
		}
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}
}
