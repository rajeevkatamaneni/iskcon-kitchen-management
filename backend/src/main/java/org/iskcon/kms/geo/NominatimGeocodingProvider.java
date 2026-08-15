package org.iskcon.kms.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Geocoding through OpenStreetMap's Nominatim (E1-S16).
 *
 * <p>Chosen for the shape of the need rather than the strength of the service: a place is looked up
 * once, while one person registers. That is a handful of requests a day, which sits inside
 * Nominatim's free tier with room to spare, and it costs nothing and needs no key.
 *
 * <p>Its usage policy asks for three things and this honours all three: an honest User-Agent naming
 * the application, no more than one request a second, and no bulk work. Results are cached because
 * places do not move — the second devotee from Jayanagar costs nothing.
 *
 * <p>Nothing here raises. A map service that is slow, rate-limited or down must not stand between a
 * devotee and their temple: the caller falls back to matching what they typed against temple names.
 */
@Component
@ConditionalOnProperty(name = "kms.geocoding.provider", havingValue = "nominatim")
public class NominatimGeocodingProvider implements GeocodingProvider {

	private static final Logger log = LoggerFactory.getLogger(NominatimGeocodingProvider.class);

	/** Their policy's limit. One person registering never approaches it; a loop would. */
	private static final long MIN_INTERVAL_MS = 1_000;

	/** Places do not move, so a hit is permanent. Bounded so a stream of nonsense cannot grow it. */
	private static final int CACHE_SIZE = 500;

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(3))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private final ObjectMapper objectMapper = new ObjectMapper();

	private final Map<String, Optional<Coordinates>> cache = Collections.synchronizedMap(
			new LinkedHashMap<>(64, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, Optional<Coordinates>> eldest) {
					return size() > CACHE_SIZE;
				}
			});

	private final String endpoint;
	private final String userAgent;
	private final String countryCodes;
	private long lastRequestAt;

	public NominatimGeocodingProvider(
			@Value("${kms.geocoding.nominatim.endpoint:https://nominatim.openstreetmap.org/search}")
			String endpoint,
			@Value("${kms.geocoding.nominatim.user-agent}") String userAgent,
			@Value("${kms.geocoding.nominatim.country-codes:in}") String countryCodes) {
		this.endpoint = endpoint;
		this.userAgent = userAgent;
		this.countryCodes = countryCodes;
	}

	@Override
	public Optional<Coordinates> locate(String place) {
		if (place == null || place.isBlank()) {
			return Optional.empty();
		}
		String key = place.trim().toLowerCase();
		Optional<Coordinates> cached = cache.get(key);
		if (cached != null) {
			return cached;
		}
		Optional<Coordinates> found = lookUp(key);
		cache.put(key, found);
		return found;
	}

	private Optional<Coordinates> lookUp(String place) {
		try {
			throttle();
			URI uri = URI.create(endpoint
					+ "?format=jsonv2&limit=1&addressdetails=0"
					+ (countryCodes.isBlank() ? "" : "&countrycodes=" + countryCodes)
					+ "&q=" + URLEncoder.encode(place, StandardCharsets.UTF_8));

			HttpRequest request = HttpRequest.newBuilder(uri)
					// Nominatim rejects anonymous callers, and rightly: a free service is entitled to
					// know who is using it.
					.header("User-Agent", userAgent)
					.header("Accept", "application/json")
					.timeout(Duration.ofSeconds(4))
					.GET()
					.build();

			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				log.warn("Geocoding {} returned {}; falling back to a name match", place, response.statusCode());
				return Optional.empty();
			}

			JsonNode results = objectMapper.readTree(response.body());
			if (!results.isArray() || results.isEmpty()) {
				return Optional.empty();
			}
			JsonNode first = results.get(0);
			return Optional.of(new Coordinates(
					first.path("lat").asDouble(), first.path("lon").asDouble()));

		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		} catch (Exception e) {
			// Deliberately broad: a devotee registering must not see a map service's bad day.
			log.warn("Geocoding {} failed ({}); falling back to a name match", place, e.toString());
			return Optional.empty();
		}
	}

	/** One request a second, as their policy asks. Only ever waits for someone who is registering. */
	private synchronized void throttle() throws InterruptedException {
		long since = System.currentTimeMillis() - lastRequestAt;
		if (since < MIN_INTERVAL_MS) {
			Thread.sleep(MIN_INTERVAL_MS - since);
		}
		lastRequestAt = System.currentTimeMillis();
	}
}
