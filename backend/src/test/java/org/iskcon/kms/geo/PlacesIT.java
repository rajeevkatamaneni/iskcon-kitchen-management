package org.iskcon.kms.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Picking a real place rather than geocoding a typed string (T-054, and the mechanism shipped
 * 2026-09-05 for the planner's delivery address).
 *
 * <p><strong>Why this file exists at all.</strong> Until T-054 there was no test anywhere for
 * {@code PlacesController} or {@code GooglePlaceSuggestionProvider} — the whole of it was
 * untested. That was survivable while it decorated one optional field on a meal plan; it stopped
 * being survivable when provisioning started depending on it for a temple's coordinates, which
 * D-17 freezes at the moment they are first entered. Replacing a weak mechanism with a better one
 * is progress; replacing a *tested* weak mechanism with an *untested* better one is not.
 *
 * <p><strong>No network anywhere in here.</strong> The controller runs against an in-memory stub
 * provider, and the one test that has to cover Google's own JSON runs against a
 * {@link HttpServer} on 127.0.0.1 serving canned replies. That is this project's standing way of
 * testing a provider that talks to somebody else's API — named as a technique rather than after
 * whichever provider it was first written for, because those get swapped — and the reason for it is
 * the one {@code MembershipIT} gives: a suite that called Google would depend on somebody else's
 * uptime, spend somebody else's quota, and fail on a train. Nothing here needs
 * {@code kms.places.provider} set, so nothing here edits shipped configuration.
 *
 * <p><strong>The confirm step is what the resolve test is really about.</strong> The defect this
 * whole design exists to prevent is a screen that shows the operator what they typed and calls it
 * confirmed. So {@link #resolveAnswersWithTheServersOwnAddressAndCoordinates()} deliberately asks
 * for a place id that looks nothing like the answer: an endpoint that echoed its input would pass
 * a laxer test and fail this one.
 *
 * <p><strong>Nothing here is an error.</strong> No map service, nothing matching, a service having
 * a bad minute — all of them are an empty answer and a plain text box, so there is no {@code KMS-}
 * code in this file and there is deliberately none to assert.
 */
@AutoConfigureMockMvc
@Import(PlacesIT.Stubs.class)
class PlacesIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private StubPlaces places;

	private JdbcTemplate admin;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		places.reset();
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- What comes back --------------------------------------------------

	@Test
	@DisplayName("a keyed deployment says so, so the box can offer suggestions before anybody types")
	void availabilityIsAnnouncedUpFront() throws Exception {
		signInAsSuperAdmin();

		mvc.perform(get("/api/v1/places/available").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.available").value(true));
	}

	@Test
	@DisplayName("no Places key is an ordinary answer — a plain text box, not a failure")
	void availabilityIsFalseWithoutAKey() throws Exception {
		// The state of every environment that has not been given a Maps key, and the case
		// provisioning must survive: the screen falls back to the typed latitude and longitude it
		// has always had, and nothing tells the operator off about it.
		places.unconfigured();
		signInAsSuperAdmin();

		mvc.perform(get("/api/v1/places/available").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.available").value(false));
	}

	@Test
	@DisplayName("suggestions come back whole — the id to resolve by, and both halves of the label")
	void suggestionsCarryTheIdAndBothHalvesOfTheLabel() throws Exception {
		signInAsSuperAdmin();

		suggest("hare krishna hill")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].placeId").value(PLACE_ID))
				.andExpect(jsonPath("$[0].description").value(DESCRIPTION))
				.andExpect(jsonPath("$[0].primary").value("ISKCON Sri Radha Krishna Temple"))
				.andExpect(jsonPath("$[0].secondary").value("Hare Krishna Hill, Bengaluru, Karnataka"));

		// The session token is the whole reason a search bills once rather than per keystroke, so
		// it has to reach the provider rather than being dropped on the way through.
		assertThat(places.asked()).containsExactly("hare krishna hill/session-1");
	}

	@Test
	@DisplayName("nothing matching is an empty list, not an error")
	void nothingMatchingIsAnEmptyList() throws Exception {
		signInAsSuperAdmin();

		suggest("qqqqqq").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	@DisplayName("resolving answers with the server's own address and coordinates, never the caller's")
	void resolveAnswersWithTheServersOwnAddressAndCoordinates() throws Exception {
		// The load-bearing test of this file. What the caller sends is an opaque id — deliberately
		// nothing like an address here — and what comes back is the place the server found. An
		// endpoint that echoed its input would satisfy a screen that then showed the operator their
		// own typing and called it confirmed, which is the defect T-042's mutation testing found and
		// the reason the confirm step exists at all.
		signInAsSuperAdmin();

		resolve(PLACE_ID)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.placeId").value(PLACE_ID))
				.andExpect(jsonPath("$.formattedAddress").value(RESOLVED_ADDRESS))
				.andExpect(jsonPath("$.at.latitude").value(13.0098))
				.andExpect(jsonPath("$.at.longitude").value(77.5511));
	}

	@Test
	@DisplayName("a place that cannot be resolved is 204, and the typed coordinates carry on")
	void resolveAnswers204WhenThePlaceCannotBeHad() throws Exception {
		// A revoked id, a quota, a bad minute. No body, no code, no error notice: the screen keeps
		// the address it has and the operator types the numbers, exactly as before this existed.
		signInAsSuperAdmin();

		resolve("ChIJ-nothing-here")
				.andExpect(status().isNoContent())
				.andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());
	}

	@Test
	@DisplayName("an unconfigured provider resolves nothing, and that is a 204 rather than a 500")
	void resolveAnswers204WithNoProviderAtAll() throws Exception {
		places.unconfigured();
		signInAsSuperAdmin();

		resolve(PLACE_ID).andExpect(status().isNoContent());
	}

	// ---- Google's own JSON, parsed against a loopback server ---------------

	@Test
	@DisplayName("Google's autocomplete and details replies are parsed, and neither shape ever raises")
	void googleRepliesAreParsed() throws Exception {
		// The one part of this feature no other test can reach. GooglePlaceSuggestionProvider is
		// @ConditionalOnProperty(havingValue = "google") and the suite deliberately never sets that,
		// so the bean is never built and every test above runs against a stub — which means nothing
		// would notice if the parse were deleted.
		//
		// This is NOT the suite reaching the network: it is an HttpServer on 127.0.0.1 answering
		// canned JSON, so the test stays hermetic and spends none of Google's quota.
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		AtomicReference<String> autocompleteBody = new AtomicReference<>("{}");
		AtomicReference<String> detailsBody = new AtomicReference<>("{}");
		AtomicInteger detailsStatus = new AtomicInteger(200);
		AtomicReference<String> lastKey = new AtomicReference<>();
		AtomicReference<String> lastFieldMask = new AtomicReference<>();
		AtomicReference<String> lastAutocompleteRequest = new AtomicReference<>();

		server.createContext("/", exchange -> {
			lastKey.set(exchange.getRequestHeaders().getFirst("X-Goog-Api-Key"));
			lastFieldMask.set(exchange.getRequestHeaders().getFirst("X-Goog-FieldMask"));
			String path = exchange.getRequestURI().getPath();
			int status = 200;
			String body;
			if (path.endsWith(":autocomplete")) {
				lastAutocompleteRequest.set(
						new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				body = autocompleteBody.get();
			} else {
				status = detailsStatus.get();
				body = detailsBody.get();
			}
			respond(exchange, status, body);
		});
		server.start();

		try {
			String base = "http://127.0.0.1:" + server.getAddress().getPort();
			GooglePlaceSuggestionProvider provider = new GooglePlaceSuggestionProvider(
					base + "/v1/places:autocomplete", base + "/v1/places/", "test-key", "in");

			assertThat(provider.configured()).isTrue();

			// The reply shaped the way the Places API (New) actually shapes one. The second entry has
			// no place id, which Google does emit for query predictions — it is skipped rather than
			// offered as something that cannot be resolved.
			autocompleteBody.set("""
					{"suggestions":[
					  {"placePrediction":{
					    "placeId":"ChIJhare-krishna-hill",
					    "text":{"text":"ISKCON Sri Radha Krishna Temple, Hare Krishna Hill, Bengaluru"},
					    "structuredFormat":{
					      "mainText":{"text":"ISKCON Sri Radha Krishna Temple"},
					      "secondaryText":{"text":"Hare Krishna Hill, Bengaluru, Karnataka, India"}}}},
					  {"queryPrediction":{"text":{"text":"temples near me"}}}
					]}""");

			List<PlaceSuggestionProvider.Suggestion> found =
					provider.suggest("hare krishna hill", "session-1");
			assertThat(found).containsExactly(new PlaceSuggestionProvider.Suggestion(
					"ChIJhare-krishna-hill",
					"ISKCON Sri Radha Krishna Temple, Hare Krishna Hill, Bengaluru",
					"ISKCON Sri Radha Krishna Temple",
					"Hare Krishna Hill, Bengaluru, Karnataka, India"));

			// The key stays on the server — this is the entire reason the browser asks us instead of
			// Google — and the field mask is the cost control, since Places bills by field returned.
			assertThat(lastKey.get()).isEqualTo("test-key");
			assertThat(lastFieldMask.get()).contains("suggestions.placePrediction.placeId");
			assertThat(lastAutocompleteRequest.get())
					.contains("\"input\":\"hare krishna hill\"")
					.contains("\"includedRegionCodes\":[\"in\"]")
					.contains("\"sessionToken\":\"session-1\"");

			// Below three characters there is nothing to go on, and every call is money.
			assertThat(provider.suggest("ha", "session-1")).isEmpty();

			// A reply with no suggestions at all, and a suggestion missing its text — an empty list
			// either way, never an exception.
			autocompleteBody.set("{}");
			assertThat(provider.suggest("hare krishna hill", "session-1")).isEmpty();

			// The details call: displayName in front of the postal address, because formattedAddress
			// is routable and missing the one word that tells a person they have arrived.
			detailsBody.set("""
					{"id":"ChIJhare-krishna-hill",
					 "displayName":{"text":"ISKCON Sri Radha Krishna Temple"},
					 "formattedAddress":"Hare Krishna Hill, Chord Rd, Bengaluru, Karnataka 560010, India",
					 "location":{"latitude":13.0098,"longitude":77.5511}}""");
			assertThat(provider.resolve("ChIJhare-krishna-hill", "session-1"))
					.contains(new PlaceSuggestionProvider.Place(
							"ChIJhare-krishna-hill",
							"ISKCON Sri Radha Krishna Temple, Hare Krishna Hill, Chord Rd, Bengaluru,"
									+ " Karnataka 560010, India",
							new GeocodingProvider.Coordinates(13.0098, 77.5511)));

			// A place whose name is already in its address is not printed twice.
			detailsBody.set("""
					{"id":"ChIJchord-road",
					 "displayName":{"text":"Chord Rd"},
					 "formattedAddress":"Chord Rd, Bengaluru, Karnataka 560010, India",
					 "location":{"latitude":13.0,"longitude":77.55}}""");
			assertThat(provider.resolve("ChIJchord-road", null))
					.map(PlaceSuggestionProvider.Place::formattedAddress)
					.contains("Chord Rd, Bengaluru, Karnataka 560010, India");

			// An address with nowhere attached is not a place: it would fill the coordinate boxes
			// with 0,0 — the Gulf of Guinea — which is worse than filling them with nothing.
			detailsBody.set("""
					{"id":"ChIJnowhere","formattedAddress":"Somewhere, India"}""");
			assertThat(provider.resolve("ChIJnowhere", null)).isEmpty();

			// And a service having a bad minute is empty, never an exception.
			detailsStatus.set(429);
			detailsBody.set("{\"error\":{\"message\":\"quota\"}}");
			assertThat(provider.resolve("ChIJhare-krishna-hill", null)).isEmpty();

			// An unkeyed provider never calls anybody at all.
			GooglePlaceSuggestionProvider unkeyed = new GooglePlaceSuggestionProvider(
					base + "/v1/places:autocomplete", base + "/v1/places/", "  ", "in");
			assertThat(unkeyed.configured()).isFalse();
			assertThat(unkeyed.suggest("hare krishna hill", "session-1")).isEmpty();
			assertThat(unkeyed.resolve("ChIJhare-krishna-hill", null)).isEmpty();

		} finally {
			server.stop(0);
		}
	}

	// ---- Who may ask (T-054) ----------------------------------------------

	@Test
	@DisplayName("the platform operator may now ask — this is the provisioning screen's picker")
	void superAdminMayAskSinceProvisioningPicksAPlace() throws Exception {
		// The authorization change itself. Before T-054 all three of these were 403 for a
		// SUPER_ADMIN: the endpoints named MANAGE_MEAL_PLANS, which no platform operator holds and
		// deliberately never will, since a temple's meal plans are the temple's own business.
		// Provisioning picking a place is the same lookup for a second job, so the endpoints name
		// both permissions rather than growing a second route to keep in step.
		signInAsSuperAdmin();

		mvc.perform(get("/api/v1/places/available").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk());
		suggest("hare krishna hill").andExpect(status().isOk());
		resolve(PLACE_ID).andExpect(status().isOk());
	}

	@Test
	@DisplayName("the planner still may — widening took nothing away from the caller it was built for")
	void kitchenManagerStillMayAsk() throws Exception {
		signInAsKitchenManager();

		mvc.perform(get("/api/v1/places/available").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk());
		suggest("hare krishna hill").andExpect(status().isOk());
		resolve(PLACE_ID).andExpect(status().isOk());
	}

	@Test
	@DisplayName("somebody holding neither permission is still refused, and spends no lookup")
	void volunteerIsRefused() throws Exception {
		// A volunteer holds neither MANAGE_MEAL_PLANS nor MANAGE_TENANTS. This is a paid lookup, so
		// widening it to a second job must not have quietly opened it to anybody with a session.
		signInAsVolunteer();

		mvc.perform(get("/api/v1/places/available").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isForbidden());
		suggest("hare krishna hill").andExpect(status().isForbidden());
		resolve(PLACE_ID).andExpect(status().isForbidden());

		assertThat(places.asked())
				.as("a refused caller must not have spent a lookup on the way to being refused")
				.isEmpty();
	}

	@Test
	@DisplayName("an unauthenticated caller cannot ask")
	void anonymousIsRefused() throws Exception {
		mvc.perform(get("/api/v1/places/suggest").param("q", "hare krishna hill"))
				.andExpect(status().isUnauthorized());
		assertThat(places.asked()).isEmpty();
	}

	// -----------------------------------------------------------------------

	/** Google's id for the one place on this stub map — opaque, and nothing like an address. */
	private static final String PLACE_ID = "ChIJhare-krishna-hill";

	private static final String DESCRIPTION =
			"ISKCON Sri Radha Krishna Temple, Hare Krishna Hill, Bengaluru, Karnataka";

	/**
	 * What the server answers with, and deliberately not what any caller sends. A test that
	 * asserted the request's own text would pass against an endpoint that echoed it.
	 */
	private static final String RESOLVED_ADDRESS =
			"ISKCON Sri Radha Krishna Temple, Hare Krishna Hill, Chord Rd, Bengaluru, "
					+ "Karnataka 560010, India";

	private ResultActions suggest(String typed) throws Exception {
		return mvc.perform(get("/api/v1/places/suggest")
				.param("q", typed)
				.param("session", "session-1")
				.header("Authorization", "Bearer valid-token"));
	}

	private ResultActions resolve(String placeId) throws Exception {
		return mvc.perform(get("/api/v1/places/{placeId}", placeId)
				.param("session", "session-1")
				.header("Authorization", "Bearer valid-token"));
	}

	private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
		byte[] out = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, out.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(out);
		}
	}

	private void signInAsSuperAdmin() {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, 'uid-super', 'Platform Operator', 'super@example.com',
						'+919000000001', 'SUPER_ADMIN', 'ACTIVE')
				""");
		stubVerifier.accept("uid-super");
	}

	private void signInAsKitchenManager() {
		stubVerifier.accept(insertTenantUser("uid-kitchen-manager", "KITCHEN_MANAGER",
				"kitchen-manager@example.com", "+919000000002"));
	}

	private void signInAsVolunteer() {
		stubVerifier.accept(insertTenantUser("uid-volunteer", "VOLUNTEER",
				"volunteer@example.com", "+919000000003"));
	}

	private String insertTenantUser(String uid, String role, String email, String phone) {
		UUID tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
				""", tenant, uid, role, email, phone, role);
		return uid;
	}

	@TestConfiguration
	static class Stubs {

		@Bean
		@Primary
		StubTokenVerifier stubTokenVerifier() {
			return new StubTokenVerifier();
		}

		@Bean
		@Primary
		StubPlaces stubPlaces() {
			return new StubPlaces();
		}
	}

	/**
	 * A Places service with one temple on it, and a record of what it was asked.
	 *
	 * <p>The counting matters as much as the answering: two of the tests above are about the lookup
	 * <em>not</em> happening — a refused caller must not have spent a paid call on the way to its
	 * 403.
	 */
	static class StubPlaces implements PlaceSuggestionProvider {

		private final List<String> asked = Collections.synchronizedList(new ArrayList<>());
		private boolean configured = true;

		void reset() {
			asked.clear();
			configured = true;
		}

		void unconfigured() {
			configured = false;
		}

		List<String> asked() {
			return List.copyOf(asked);
		}

		@Override
		public boolean configured() {
			return configured;
		}

		@Override
		public List<Suggestion> suggest(String typed, String sessionToken) {
			asked.add(typed + "/" + sessionToken);
			if (!configured || !"hare krishna hill".equalsIgnoreCase(typed.trim())) {
				return List.of();
			}
			return List.of(new Suggestion(
					PLACE_ID,
					DESCRIPTION,
					"ISKCON Sri Radha Krishna Temple",
					"Hare Krishna Hill, Bengaluru, Karnataka"));
		}

		@Override
		public Optional<Place> resolve(String placeId, String sessionToken) {
			if (!configured || !PLACE_ID.equals(placeId)) {
				return Optional.empty();
			}
			return Optional.of(new Place(
					placeId, RESOLVED_ADDRESS, new GeocodingProvider.Coordinates(13.0098, 77.5511)));
		}
	}

	static class StubTokenVerifier implements TokenVerifier {

		private final Map<String, VerifiedSubject> accepted = new HashMap<>();

		void accept(String uid) {
			accepted.put("valid-token", new VerifiedSubject(uid, uid + "@example.com", "+919000000000"));
		}

		void reset() {
			accepted.clear();
		}

		@Override
		public VerifiedSubject verify(String idToken) throws InvalidTokenException {
			VerifiedSubject subject = accepted.get(idToken);
			if (subject == null) {
				throw new InvalidTokenException("Unrecognised token");
			}
			return subject;
		}
	}
}
