package org.iskcon.kms.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Address suggestions from the Places API (New), proxied so no key reaches a browser.
 *
 * <p><strong>Places API (New), not the legacy Autocomplete.</strong> The old
 * {@code /maps/api/place/autocomplete/json} endpoint is on the same legacy footing as Directions and
 * Distance Matrix and is unavailable in new Cloud projects, so it was never an option. The new API
 * is two POSTs — {@code :autocomplete} while somebody types, {@code /places/{id}} once they pick —
 * with a field mask on each, because it bills by the fields returned.
 *
 * <p><strong>Biased to India, and to the temple's own city where we know it.</strong> Every temple
 * on this platform is in India, and an unbiased search offers a Bengaluru in Texas. The country
 * restriction is the floor; a delivery is nearly always within an hour of the temple.
 *
 * <p><strong>The field mask is the cost control.</strong> {@code :autocomplete} asks only for the
 * suggestion text and the place id, and the details call asks only for the formatted address and the
 * location. Asking for a photo or the opening hours of a housing society would be money spent on
 * something nobody prints.
 *
 * <p><strong>Nothing here raises.</strong> A failure is an empty list, and an empty list is a plain
 * text box — the behaviour a temple with no key gets, which is a working address field. A map
 * service having a bad minute must never stand between a planner and a meal plan.
 */
@Component
@ConditionalOnProperty(name = "kms.places.provider", havingValue = "google")
public class GooglePlaceSuggestionProvider implements PlaceSuggestionProvider {

	private static final Logger log = LoggerFactory.getLogger(GooglePlaceSuggestionProvider.class);

	/** Below this there is nothing to go on and every query is a wasted call. */
	private static final int MIN_QUERY = 3;

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(3))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private final ObjectMapper objectMapper = new ObjectMapper();

	private final String autocompleteEndpoint;
	private final String detailsEndpoint;
	private final String apiKey;
	private final String region;

	public GooglePlaceSuggestionProvider(
			@Value("${kms.places.google.autocomplete-endpoint:https://places.googleapis.com/v1/places:autocomplete}")
			String autocompleteEndpoint,
			@Value("${kms.places.google.details-endpoint:https://places.googleapis.com/v1/places/}")
			String detailsEndpoint,
			@Value("${kms.places.google.api-key:}") String apiKey,
			@Value("${kms.places.google.region:in}") String region) {
		this.autocompleteEndpoint = autocompleteEndpoint;
		this.detailsEndpoint = detailsEndpoint;
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		this.region = region == null || region.isBlank() ? "in" : region.trim();
	}

	@Override
	public boolean configured() {
		return !apiKey.isEmpty();
	}

	@Override
	public List<Suggestion> suggest(String typed, String sessionToken) {
		String query = typed == null ? "" : typed.trim();
		if (!configured() || query.length() < MIN_QUERY) {
			return List.of();
		}
		String body = "{\"input\":" + json(query)
				+ ",\"includedRegionCodes\":[" + json(region) + "]"
				+ (sessionToken == null || sessionToken.isBlank()
						? "" : ",\"sessionToken\":" + json(sessionToken))
				+ "}";
		JsonNode root = post(autocompleteEndpoint, body,
				"suggestions.placePrediction.placeId,"
						+ "suggestions.placePrediction.text.text,"
						+ "suggestions.placePrediction.structuredFormat");
		if (root == null) {
			return List.of();
		}
		List<Suggestion> out = new ArrayList<>();
		for (JsonNode node : root.path("suggestions")) {
			JsonNode p = node.path("placePrediction");
			String id = p.path("placeId").asText(null);
			String description = p.path("text").path("text").asText(null);
			if (id == null || description == null) {
				continue;
			}
			out.add(new Suggestion(
					id,
					description,
					p.path("structuredFormat").path("mainText").path("text").asText(description),
					p.path("structuredFormat").path("secondaryText").path("text").asText("")));
		}
		return out;
	}

	@Override
	public Optional<Place> resolve(String placeId, String sessionToken) {
		if (!configured() || placeId == null || placeId.isBlank()) {
			return Optional.empty();
		}
		String url = detailsEndpoint + enc(placeId)
				+ (sessionToken == null || sessionToken.isBlank()
						? "" : "?sessionToken=" + enc(sessionToken));
		JsonNode root = get(url, "id,displayName,formattedAddress,location");
		if (root == null) {
			return Optional.empty();
		}
		String address = root.path("formattedAddress").asText(null);
		JsonNode location = root.path("location");
		if (address == null || location.isMissingNode()) {
			return Optional.empty();
		}
		return Optional.of(new Place(
				root.path("id").asText(placeId),
				withName(root.path("displayName").path("text").asText(null), address),
				new GeocodingProvider.Coordinates(
						sixDecimals(location.path("latitude").asDouble()),
						sixDecimals(location.path("longitude").asDouble()))));
	}

	/**
	 * Google's degrees, cut to the six decimal places the temple is going to keep.
	 *
	 * <p><strong>Why here, and not in the screen that shows it.</strong> Places answers with the
	 * shortest decimal that names its own double, and for a great many places that is seventeen
	 * significant digits of which the last three are float noise from a computation that was never
	 * ours. Rajeev picked ISKCON - Mysuru on the provisioning screen and the latitude box filled with
	 * a fifteen-digit number. That number is then shown to an operator under the words <em>is this
	 * the right place?</em>, and a person cannot check what they cannot read: the digits past the
	 * sixth are noise dressed as precision, and they crowd out the ones that carry the answer.
	 *
	 * <p><strong>Why six and not five or seven.</strong> The sixth decimal of a degree is about
	 * eleven centimetres. The only machines that read this are the Vaishnava calendar, which wants
	 * degrees for a sunrise, and the Routes call, which wants a street; neither can tell eleven
	 * centimetres from nothing. The tenant column is {@code NUMERIC(9,6)} besides, so six is what the
	 * database was always going to store — this makes the operator's eye and the row agree, which is
	 * the whole of it, and it matters because D-17 freezes these two numbers at provisioning and
	 * there is deliberately no screen that can tidy them up afterwards.
	 *
	 * <p><strong>Rounded, never formatted.</strong> {@code BigDecimal.valueOf} reads the double's own
	 * shortest rendering and {@link java.math.RoundingMode#HALF_UP} cuts it; going back to a double
	 * drops any trailing zeros the scale introduced. So a coordinate that was already short stays
	 * short, where a {@code %.6f} would have padded it out to six places it never had and made a
	 * neatly typed number look machine-generated.
	 *
	 * <p>A value that is not finite is handed back untouched rather than rounded: {@code
	 * BigDecimal.valueOf} raises on those, and this class's standing promise is that a bad reply from
	 * a map service is an empty answer and never an exception. Such a reply fails the column's range
	 * check a moment later, which is the right place for it to fail.
	 */
	private static double sixDecimals(double degrees) {
		if (!Double.isFinite(degrees)) {
			return degrees;
		}
		return BigDecimal.valueOf(degrees).setScale(6, RoundingMode.HALF_UP).doubleValue();
	}

	/**
	 * The place's name in front of its address, where Google left it out.
	 *
	 * <p>Found on staging with Rajeev's own example: the autocomplete row reads "Mantri Serenity,
	 * Kanakapura Main Road, …" and the details call answers "Kanakapura Main Rd, Doddakallasandra,
	 * Subramanyapura, Bengaluru, Karnataka 560062, India" — correct, routable, and missing the one
	 * word a driver needs to know they have arrived. {@code formattedAddress} is postal; a housing
	 * society's name lives in {@code displayName}.
	 *
	 * <p>Only prefixed when the address does not already carry it, so a place whose name IS its
	 * street does not print twice.
	 */
	private static String withName(String name, String address) {
		if (name == null || name.isBlank() || address.toLowerCase().contains(name.toLowerCase())) {
			return address;
		}
		return name + ", " + address;
	}

	private JsonNode post(String url, String body, String fieldMask) {
		return send(HttpRequest.newBuilder(URI.create(url))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)), fieldMask);
	}

	private JsonNode get(String url, String fieldMask) {
		return send(HttpRequest.newBuilder(URI.create(url)).GET(), fieldMask);
	}

	private JsonNode send(HttpRequest.Builder request, String fieldMask) {
		try {
			HttpResponse<String> response = http.send(
					request.timeout(Duration.ofSeconds(5))
							.header("X-Goog-Api-Key", apiKey)
							.header("X-Goog-FieldMask", fieldMask)
							.build(),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

			if (response.statusCode() != 200) {
				// Logged at warn with the body trimmed: a wrong field mask and a disabled API are both
				// 400s that say exactly which, and finding that out from a log beats guessing.
				log.warn("Places answered {}: {}", response.statusCode(), trim(response.body()));
				return null;
			}
			return objectMapper.readTree(response.body());

		} catch (Exception e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			log.warn("Places could not be reached ({}); the address box carries on without suggestions",
					e.toString());
			return null;
		}
	}

	private static String trim(String body) {
		if (body == null) {
			return "";
		}
		return body.length() <= 300 ? body : body.substring(0, 300) + "…";
	}

	/** Minimal JSON string escaping — everything sent here is one short typed line. */
	private static String json(String s) {
		StringBuilder out = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
				}
			}
		}
		return out.append('"').toString();
	}

	private static String enc(String s) {
		return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
	}
}
