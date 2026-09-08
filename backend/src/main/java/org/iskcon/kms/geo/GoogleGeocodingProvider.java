package org.iskcon.kms.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * Turning a typed place into coordinates, through the Google Geocoding API.
 *
 * <p><strong>Why Google and not the free service it replaces.</strong> Everything else on this
 * platform that touches a map is already Google — the delivery address is picked from Places, the
 * leave-by time comes from Routes, the job card's map is a Static Map — and one of those, Places,
 * was adopted precisely because the previous geocoder's coverage of Bengaluru apartment complexes
 * was thin enough to fail silently (V93). Keeping a second, weaker map behind the same port meant
 * two services could disagree about where the same address is, on the same screen, a second apart.
 * This is on the key the rest of Maps already uses, so the deployment gains a variable and not a
 * vendor.
 *
 * <p><strong>Restricted to India rather than merely biased towards it.</strong> Every temple on this
 * platform is in India, and an unbiased search offers a Bengaluru in Texas. {@code components=
 * country:in} is a restriction — a result outside it is not ranked lower, it is not returned — which
 * is the same shape as the {@code includedRegionCodes} the Places provider sends, and the same
 * behaviour the previous provider's country filter had.
 *
 * <p><strong>A 200 from this API is not a success, and that is the trap worth naming.</strong>
 * Unlike the Places API (New), which answers a bad key with a 400, the Geocoding API answers almost
 * everything with HTTP 200 and puts the verdict in a {@code status} field —
 * {@code REQUEST_DENIED} for a key that is not permitted, {@code OVER_QUERY_LIMIT} for an exhausted
 * quota, {@code ZERO_RESULTS} for an honest miss. A reader that trusted the status line and went
 * straight for {@code geometry.location} would find nothing there, and Jackson's {@code asDouble()}
 * answers {@code 0.0} for a node that is absent — which is a real place, in the Gulf of Guinea, and
 * would be written onto a temple as its location. So {@code status} is checked before anything is
 * read, and only {@code OK} is a hit.
 *
 * <p><strong>Results are cached, because a place does not move and every lookup is billed.</strong>
 * The second devotee searching from Jayanagar costs nothing. Two things about the cache are
 * deliberate: it is bounded, so a stream of nonsense cannot grow it; and entries expire, because
 * Google's terms allow geocoding content to be cached temporarily for performance and not kept
 * indefinitely — the free provider this replaces treated a hit as permanent, which its licence
 * allowed and this one does not. Misses are cached too: a typo retried in a loop should cost one
 * request, not one per keystroke.
 *
 * <p><strong>Nothing here raises.</strong> A timeout, a quota, a bad key, a malformed reply — each
 * is {@link Optional#empty()}, and the callers already know what to do with it: the devotee search
 * falls back to matching what was typed against temple names, and the delivery estimate reports
 * KMS-400078 and saves the plan anyway. The same contract {@link GoogleStaticMapProvider} and
 * {@link GooglePlaceSuggestionProvider} keep, for the same reason — a map service having a bad
 * minute must not stand between a person and their temple.
 */
@Component
@ConditionalOnProperty(name = "kms.geocoding.provider", havingValue = "google")
public class GoogleGeocodingProvider implements GeocodingProvider {

	private static final Logger log = LoggerFactory.getLogger(GoogleGeocodingProvider.class);

	/** Bounded so a stream of nonsense cannot grow it. Places, not queries, is the real cardinality. */
	private static final int CACHE_SIZE = 500;

	/**
	 * How long a cached answer stays good. Google's terms permit temporary caching of geocoding
	 * content for performance, up to thirty days; this sits inside that with a fortnight to spare, so
	 * a long-lived instance cannot quietly turn a cache into a copy of their data.
	 */
	private static final long MAX_AGE_MS = Duration.ofDays(25).toMillis();

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(3))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private final ObjectMapper objectMapper = new ObjectMapper();

	// Holds the whole answer, coordinates and description together. One entry per place either way:
	// caching the two separately would ask Google twice for one lookup, and every ask is billed.
	private final Map<String, Cached> cache = Collections.synchronizedMap(
			new LinkedHashMap<>(64, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
					return size() > CACHE_SIZE;
				}
			});

	private final String endpoint;
	private final String apiKey;
	private final String region;

	public GoogleGeocodingProvider(
			@Value("${kms.geocoding.google.endpoint:https://maps.googleapis.com/maps/api/geocode/json}")
			String endpoint,
			@Value("${kms.geocoding.google.api-key:}") String apiKey,
			@Value("${kms.geocoding.google.region:in}") String region) {
		this.endpoint = endpoint;
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		this.region = region == null || region.isBlank() ? "in" : region.trim();
	}

	/**
	 * Whether this deployment can look anything up at all.
	 *
	 * <p>Selecting {@code google} without a key is not a working geocoder, and saying so matters
	 * rather than being tidy: {@code MealPlanService} shows KMS-400078 — <em>that address could not
	 * be found</em> — only when something actually looked. See {@link GeocodingProvider#configured()}.
	 */
	@Override
	public boolean configured() {
		return !apiKey.isEmpty();
	}

	@Override
	public Optional<Coordinates> locate(String place) {
		return describe(place).map(Located::at);
	}

	/**
	 * The coordinates and Google's own rendering of what it decided the place was.
	 *
	 * <p>Overridden rather than left to the port's default because the answer already carries it:
	 * {@code formatted_address} comes back in the same reply, for the same one billed lookup, and a
	 * person asked to confirm "Mysuru, Karnataka" can do so where a person asked to confirm
	 * {@code 12.2958, 76.6394} can only nod.
	 */
	@Override
	public Optional<Located> describe(String place) {
		if (!configured() || place == null || place.isBlank()) {
			return Optional.empty();
		}
		String key = place.trim().toLowerCase();
		Cached cached = cache.get(key);
		if (cached != null && !cached.isStale()) {
			return cached.answer();
		}
		Optional<Located> found = lookUp(key);
		cache.put(key, new Cached(found, System.currentTimeMillis()));
		return found;
	}

	private Optional<Located> lookUp(String place) {
		try {
			URI uri = URI.create(endpoint
					+ "?address=" + enc(place)
					// A restriction, not a bias: a match outside India is not returned at all.
					+ "&components=" + enc("country:" + region)
					+ "&key=" + enc(apiKey));

			HttpResponse<String> response = http.send(
					HttpRequest.newBuilder(uri)
							.header("Accept", "application/json")
							.timeout(Duration.ofSeconds(5))
							.GET()
							.build(),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

			if (response.statusCode() != 200) {
				log.warn("Geocoding {} returned HTTP {}; falling back to a name match",
						place, response.statusCode());
				return Optional.empty();
			}

			JsonNode root = objectMapper.readTree(response.body());
			String status = root.path("status").asText("");
			if (!"OK".equals(status)) {
				// ZERO_RESULTS is an ordinary miss and says nothing worth a log line. Everything else
				// is our problem rather than the caller's — a key that is not permitted, an exhausted
				// quota, an API not enabled on the project — and error_message says which, which beats
				// guessing from a graph of green requests that return nothing.
				if (!"ZERO_RESULTS".equals(status)) {
					log.warn("Geocoding {} answered {} ({}); falling back to a name match",
							place, status, root.path("error_message").asText(""));
				}
				return Optional.empty();
			}

			JsonNode location = root.path("results").path(0).path("geometry").path("location");
			if (!location.hasNonNull("lat") || !location.hasNonNull("lng")) {
				// OK with nothing under it should not happen, and if it ever does the wrong answer is
				// 0,0 — the Gulf of Guinea — written onto a temple. Checked rather than trusted.
				log.warn("Geocoding {} answered OK with no location; falling back to a name match", place);
				return Optional.empty();
			}

			// Google's normalised rendering of what it matched. Absent or blank is a hit with nothing
			// to show rather than a failed lookup: the coordinates are still right, and a caller that
			// wanted a label simply does not get one.
			String formatted = root.path("results").path(0).path("formatted_address").asText(null);
			return Optional.of(new Located(
					new Coordinates(
							sixDecimals(location.get("lat").asDouble()),
							sixDecimals(location.get("lng").asDouble())),
					formatted == null || formatted.isBlank() ? null : formatted));

		} catch (Exception e) {
			// Deliberately broad, and the interrupt is restored rather than swallowed: a devotee
			// registering must not see a map service's bad day.
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			log.warn("Geocoding {} failed ({}); falling back to a name match", place, e.toString());
			return Optional.empty();
		}
	}

	/**
	 * Google's degrees, cut to the six decimal places anything downstream can actually hold.
	 *
	 * <p><strong>The same cut {@link GooglePlaceSuggestionProvider} makes, for the same reason.</strong>
	 * Both providers hand back the same {@link Coordinates} record and both of them feed columns
	 * declared {@code NUMERIC(9,6)} — {@code tenants.latitude} since V1, {@code
	 * meal_plans.delivery_latitude} since V88 — so six decimals is what is kept whichever service
	 * answered. Two providers behind one port that disagreed about how precise an answer is would be
	 * exactly the confusion the port exists to prevent, and the difference would show up as a screen
	 * where the number an operator was shown is not the number that was saved.
	 *
	 * <p><strong>What this one actually carries.</strong> The Geocoding API is the milder case: it
	 * renders seven decimals, so it produces {@code 76.6340866} rather than the seventeen significant
	 * digits Places produces for the same place. That is still one more decimal than anything here
	 * stores, and about a centimetre of it is real; it is cut here rather than left for the database
	 * to cut silently, so that whatever a caller shows and whatever the row holds are the one number.
	 *
	 * <p>Rounded rather than formatted, so a coordinate that was already short stays short, and a
	 * value that is not finite is handed back untouched — {@code BigDecimal.valueOf} raises on those,
	 * and nothing in this class may raise. See {@link GooglePlaceSuggestionProvider} for the longer
	 * version of both arguments; T-059 made the cut, after Rajeev picked a temple on the provisioning
	 * screen and got a fifteen-digit latitude to confirm.
	 */
	private static double sixDecimals(double degrees) {
		if (!Double.isFinite(degrees)) {
			return degrees;
		}
		return BigDecimal.valueOf(degrees).setScale(6, RoundingMode.HALF_UP).doubleValue();
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	/** One answer and when it was learned. Held together so an entry cannot outlive its timestamp. */
	private record Cached(Optional<Located> answer, long learnedAt) {

		boolean isStale() {
			return System.currentTimeMillis() - learnedAt > MAX_AGE_MS;
		}
	}
}
