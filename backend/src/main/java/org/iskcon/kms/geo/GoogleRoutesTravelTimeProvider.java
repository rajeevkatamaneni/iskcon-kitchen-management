package org.iskcon.kms.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Traffic-aware driving time through Google's Routes API (E4-S16).
 *
 * <p><strong>Why Routes and not the obvious two.</strong> The Directions API and the Distance Matrix
 * API went Legacy on 1 March 2025 and are not available in new Cloud projects at all, so they were
 * never options. {@code computeRoutes} with {@code travelMode: DRIVE},
 * {@code routingPreference: TRAFFIC_AWARE_OPTIMAL} and a future {@code departureTime} gives a
 * traffic-aware prediction for the hour the food actually leaves, which is the whole point — the
 * same road out of Rajajinagar is twenty minutes at eleven and fifty at six.
 *
 * <p><strong>Why two calls.</strong> {@code trafficModel} takes one value per request, so the range
 * the screen shows is {@code OPTIMISTIC} and then {@code PESSIMISTIC}. One call would give a single
 * confident number, and a single number about traffic is a promise nobody can keep. Both calls ask
 * for a field mask of {@code routes.duration} and nothing else: Routes bills by the fields returned,
 * and the polyline, the legs and the steps are of no use to somebody deciding when to leave.
 *
 * <p><strong>Nothing here raises, and nothing here is stored.</strong> The first is the port's
 * contract (E4-S16 D3) and the reason the deliberately broad catch below is deliberate: a bad minute
 * at a map service must not stand between a cook and a meal plan. The second is the licence — Maps
 * Platform ToS §3.2.3(b) permits no caching except where expressly allowed, and the Routes clause
 * permits coordinates only, conspicuously not durations. So this asks every time it is asked, and
 * remembers nothing.
 *
 * <p><strong>Authentication, two ways, neither required to build this object.</strong> The
 * preference is Application Default Credentials — on Cloud Run that is the service account already
 * attached to the revision, so there is no secret to store, none to rotate and no static-egress-IP
 * machinery an IP-restricted key would demand. An API key from configuration is the fallback,
 * because Google's own documentation has no Routes-specific OAuth page and it is not certain the
 * scope works; that is a build-time question and one call settles it. Whichever is used, credentials
 * are resolved lazily and a failure to resolve them is an empty answer like any other, so the
 * absence of both is a deployment with no estimate rather than a deployment that will not start.
 */
@Component
@ConditionalOnProperty(name = "kms.travel-time.provider", havingValue = "google-routes")
public class GoogleRoutesTravelTimeProvider implements TravelTimeProvider {

	private static final Logger log = LoggerFactory.getLogger(GoogleRoutesTravelTimeProvider.class);

	/** The one field worth paying for: how long the drive takes. */
	private static final String FIELD_MASK = "routes.duration";

	private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(3))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private final ObjectMapper objectMapper = new ObjectMapper();

	private final String endpoint;
	private final String apiKey;

	/**
	 * Which project the call is billed and rate-limited against. Cloud Run's own service account
	 * carries its project, so this is empty there and nothing is sent. It matters for a developer
	 * running on their own `gcloud` login: user credentials belong to a person and not a project,
	 * and Google answers 403 USER_PROJECT_DENIED without being told which project to charge.
	 * Verified against the live Routes API on 2026-09-04.
	 */
	private final String quotaProject;

	public GoogleRoutesTravelTimeProvider(
			@Value("${kms.travel-time.google.endpoint:https://routes.googleapis.com/directions/v2:computeRoutes}")
			String endpoint,
			@Value("${kms.travel-time.google.api-key:}") String apiKey,
			@Value("${kms.gcp.project-id:}") String quotaProject) {
		this.endpoint = endpoint;
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		this.quotaProject = quotaProject == null ? "" : quotaProject.trim();
	}

	@Override
	public Optional<TravelTime> drive(
			GeocodingProvider.Coordinates origin, GeocodingProvider.Coordinates destination,
			Instant departAt) {

		if (origin == null || destination == null || departAt == null) {
			return Optional.empty();
		}
		// A departure in the past is refused by the service, and the caller cannot always know how
		// late it is by the time the request is made.
		Instant depart = departAt.isAfter(Instant.now()) ? departAt : Instant.now().plusSeconds(60);

		Optional<Duration> optimistic = duration(origin, destination, depart, "OPTIMISTIC");
		Optional<Duration> pessimistic = duration(origin, destination, depart, "PESSIMISTIC");
		if (optimistic.isEmpty() || pessimistic.isEmpty()) {
			return Optional.empty();
		}
		// Ordered rather than trusted. The two models are two predictions of the same drive, and
		// nothing guarantees which comes back longer; a range printed backwards would read as a bug
		// in the kitchen's eyes and be one in ours.
		Duration low = optimistic.get();
		Duration high = pessimistic.get();
		return Optional.of(low.compareTo(high) <= 0
				? new TravelTime(low, high)
				: new TravelTime(high, low));
	}

	private Optional<Duration> duration(
			GeocodingProvider.Coordinates origin, GeocodingProvider.Coordinates destination,
			Instant departAt, String trafficModel) {

		try {
			String body = """
					{"origin":{"location":{"latLng":{"latitude":%s,"longitude":%s}}},\
					"destination":{"location":{"latLng":{"latitude":%s,"longitude":%s}}},\
					"travelMode":"DRIVE","routingPreference":"TRAFFIC_AWARE_OPTIMAL",\
					"departureTime":"%s","trafficModel":"%s"}"""
					.formatted(origin.latitude(), origin.longitude(),
							destination.latitude(), destination.longitude(),
							departAt.toString(), trafficModel);

			HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
					.header("Content-Type", "application/json")
					.header("X-Goog-FieldMask", FIELD_MASK)
					.timeout(Duration.ofSeconds(4))
					.POST(HttpRequest.BodyPublishers.ofString(body));

			if (!authorise(request)) {
				return Optional.empty();
			}

			HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				log.warn("Routes returned {} for the {} model; the planner shows no estimate",
						response.statusCode(), trafficModel);
				return Optional.empty();
			}

			JsonNode routes = objectMapper.readTree(response.body()).path("routes");
			if (!routes.isArray() || routes.isEmpty()) {
				return Optional.empty();
			}
			// Protobuf duration: seconds with a trailing s, "2145s".
			String text = routes.get(0).path("duration").asText("");
			if (!text.endsWith("s")) {
				return Optional.empty();
			}
			return Optional.of(Duration.ofSeconds(
					(long) Double.parseDouble(text.substring(0, text.length() - 1))));

		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		} catch (Exception e) {
			// Deliberately broad, exactly as the geocoder's is: a cook planning tomorrow's delivery
			// must not see a map service's bad day, and an estimate is advice rather than a rule.
			log.warn("Routes ({}) failed ({}); the planner shows no estimate", trafficModel, e.toString());
			return Optional.empty();
		}
	}

	/**
	 * The service account first, an API key second, and false when neither can be had — which is a
	 * deployment with no estimate, not a deployment that fails.
	 */
	private boolean authorise(HttpRequest.Builder request) {
		if (apiKey.isEmpty()) {
			try {
				GoogleCredentials credentials =
						GoogleCredentials.getApplicationDefault().createScoped(List.of(SCOPE));
				credentials.refreshIfExpired();
				request.header("Authorization",
						"Bearer " + credentials.getAccessToken().getTokenValue());
				if (!quotaProject.isEmpty()) {
					request.header("X-Goog-User-Project", quotaProject);
				}
				return true;
			} catch (Exception e) {
				log.warn("No credentials for the Routes API ({}); the planner shows no estimate",
						e.toString());
				return false;
			}
		}
		request.header("X-Goog-Api-Key", apiKey);
		return true;
	}
}
