package org.iskcon.kms.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
 * Looking up a temple's coordinates from its address (T-042, D-17).
 *
 * <p><strong>No network anywhere in here, and that is a requirement rather than a convenience.</strong>
 * The geocoder and the map service are both in-memory stubs, for the reason {@code MembershipIT}
 * already gives about its own: a suite that called OpenStreetMap would depend on somebody else's
 * uptime and spend somebody else's rate limit, and would fail on a train. The same code paths run
 * that a deployment with {@code GEOCODING_PROVIDER=nominatim} would run.
 *
 * <p><strong>The unkeyed case is the real one.</strong> {@code kms.static-map.provider} is
 * {@code none} in every environment today, so the test that matters most here is the one asserting a
 * hit with no picture: a found address must still come back, with coordinates, and the absence of a
 * map must change nothing else about the answer.
 *
 * <p><strong>Every miss is a 200.</strong> Not finding an address is an answer, not an error —
 * provisioning a temple must never be blocked by a map service — so there is no {@code KMS-} code in
 * this file and there is deliberately none to assert.
 */
@AutoConfigureMockMvc
@Import(GeocodingIT.Stubs.class)
class GeocodingIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private StubGeocoder geocoder;

	@Autowired
	private StubStaticMap staticMap;

	private JdbcTemplate admin;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		geocoder.reset();
		staticMap.reset();
		signInAsSuperAdmin();
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- What comes back --------------------------------------------------

	@Test
	@DisplayName("an address the geocoder knows comes back as coordinates and a map to confirm them by")
	void findsAnAddressAndDrawsIt() throws Exception {
		staticMap.serve(PNG);

		geocode("Bengaluru, Karnataka")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(true))
				.andExpect(jsonPath("$.latitude").value(12.9716))
				.andExpect(jsonPath("$.longitude").value(77.5946))
				// The whole picture inside the JSON, the way JobCardService.mapFor inlines the
				// delivery sheet's. An <img src> pointing at an endpoint could not carry a bearer
				// token, and everything here is behind MANAGE_TENANTS.
				.andExpect(jsonPath("$.mapDataUri")
						.value("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(PNG)))
				.andExpect(jsonPath("$.resolvedAddress")
						.value("ISKCON Temple, Hare Krishna Hill, Bengaluru, Karnataka, 560010, India"));

		assertThat(geocoder.asked()).containsExactly("Bengaluru, Karnataka");
		// Drawn around the coordinates that were found, not around anything the caller sent.
		assertThat(staticMap.centredOn())
				.isEqualTo(new GeocodingProvider.Coordinates(12.9716, 77.5946));
	}

	@Test
	@DisplayName("with no static-map key — every environment today — the coordinates still come back")
	void findsAnAddressWithoutAKey() throws Exception {
		// The state of staging and production as this ships: kms.static-map.provider is 'none'.
		// A missing key must cost the confirmation its picture and nothing else — not the lookup,
		// not the coordinates, and not the operator's ability to go on and provision the temple.
		staticMap.unconfigured();

		geocode("Bengaluru, Karnataka")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(true))
				.andExpect(jsonPath("$.latitude").value(12.9716))
				.andExpect(jsonPath("$.longitude").value(77.5946))
				.andExpect(jsonPath("$.mapDataUri").doesNotExist())
				// The whole of what makes an unkeyed deployment usable, and what this task's negative
				// control caught missing: without it the operator is asked to vouch for two
				// six-decimal numbers, which anybody would simply accept. A wrong resolved address is
				// as obvious to a person as a wrong pin.
				.andExpect(jsonPath("$.resolvedAddress")
						.value("ISKCON Temple, Hare Krishna Hill, Bengaluru, Karnataka, 560010, India"));

		assertThat(staticMap.centredOn())
				.as("an unconfigured map service is not called at all, rather than called and ignored")
				.isNull();
	}

	@Test
	@DisplayName("a map service that has a bad minute costs the picture and nothing else")
	void findsAnAddressWhenTheMapFails() throws Exception {
		// Configured, asked, and answered with nothing — a quota, a bad key, a timeout. Distinct
		// from the case above because this one does reach the service, and the answer must be the
		// same either way.
		staticMap.serve(null);

		geocode("Bengaluru, Karnataka")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(true))
				.andExpect(jsonPath("$.mapDataUri").doesNotExist());
	}

	@Test
	@DisplayName("an address nothing matches is an ordinary answer, not an error")
	void reportsAMissWithoutFailing() throws Exception {
		// The one behaviour the whole feature hangs off: provisioning is never blocked by this.
		// DELIVERY_ADDRESS_NOT_FOUND (KMS-400078) is the precedent for a lookup that misses costing
		// a convenience and nothing else — and this one does not even warrant a code, because the
		// operator simply carries on typing the coordinates themselves.
		geocode("Nowhere At All")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(false))
				.andExpect(jsonPath("$.latitude").doesNotExist())
				.andExpect(jsonPath("$.longitude").doesNotExist())
				.andExpect(jsonPath("$.resolvedAddress").doesNotExist())
				.andExpect(jsonPath("$.mapDataUri").doesNotExist());
	}

	@Test
	@DisplayName("a deployment with no geocoder at all answers the same way, and never says 'not found'")
	void reportsNothingWhenNoGeocoderIsConfigured() throws Exception {
		// NoGeocodingProvider is the default and is live in every environment today. It must not be
		// an error either: the endpoint answers 200 with nothing found, and it is the *words on the
		// screen* that must not claim an address could not be found when nobody looked — which is
		// why there is no field here distinguishing the two and the screen's copy says only that no
		// coordinates came back.
		geocoder.unconfigured();

		geocode("Bengaluru, Karnataka")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(false))
				.andExpect(jsonPath("$.mapDataUri").doesNotExist());
	}

	@Test
	@DisplayName("an empty box is somebody who has not typed yet, not a bad request")
	void blankAddressIsNotAnError() throws Exception {
		geocode("   ").andExpect(status().isOk()).andExpect(jsonPath("$.found").value(false));

		mvc.perform(get("/api/v1/geocode").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(false));

		assertThat(geocoder.asked())
				.as("nobody's free service is called for an empty box")
				.isEmpty();
	}

	@Test
	@DisplayName("a geocoder that gives a position and no label is a hit, not a miss")
	void findsAnAddressWithNoDescription() throws Exception {
		// The weakest of the three confirmations, and still an answer: the coordinates came from a
		// machine rather than from somebody typing them. The screen shows them and says plainly that
		// they want checking, rather than pretending two numbers are a confirmation.
		staticMap.unconfigured();
		geocoder.withoutADescription();

		geocode("Bengaluru, Karnataka")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(true))
				.andExpect(jsonPath("$.latitude").value(12.9716))
				.andExpect(jsonPath("$.resolvedAddress").doesNotExist())
				.andExpect(jsonPath("$.mapDataUri").doesNotExist());
	}

	@Test
	@DisplayName("a provider that only implements locate() still works, and describes nothing")
	void theDefaultDescriptionIsAPositionWithNoLabel() {
		// Why describe() was added as a *default*: this is the shape every other implementation and
		// every other caller is in. NoGeocodingProvider, MembershipService and MealPlanService were
		// not touched by T-042 and must not need to be — and the interface has to stay a functional
		// one, or the test stubs written as lambdas stop compiling. Written as a lambda here for
		// exactly that reason.
		GeocodingProvider onlyLocate = place -> Optional.of(new GeocodingProvider.Coordinates(1, 2));

		assertThat(onlyLocate.describe("anywhere"))
				.contains(new GeocodingProvider.Located(new GeocodingProvider.Coordinates(1, 2), null));
		assertThat(onlyLocate.describe("anywhere").map(GeocodingProvider.Located::at))
				.as("the default must agree with locate() about where the place is")
				.isEqualTo(onlyLocate.locate("anywhere"));
	}

	@Test
	@DisplayName("Nominatim's display_name is read from the reply, and its absence is not a failure")
	void nominatimReadsTheDisplayName() throws Exception {
		// The one line of this feature no other test can reach. NominatimGeocodingProvider is
		// @ConditionalOnProperty(havingValue = "nominatim") and the suite deliberately never sets
		// that, so the bean is never built and every other test here runs against a stub — which
		// means nothing would notice if the display_name read were deleted.
		//
		// This is NOT the suite reaching the network. It is a HttpServer on 127.0.0.1 answering
		// canned JSON, so the test stays hermetic, runs on a train, and spends none of
		// OpenStreetMap's rate limit. What it covers is the parse: that display_name is read, that a
		// reply without one is still a hit, and that nothing anywhere raises.
		com.sun.net.httpserver.HttpServer server =
				com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		java.util.concurrent.atomic.AtomicReference<String> body = new java.util.concurrent.atomic.AtomicReference<>("[]");
		server.createContext("/search", exchange -> {
			byte[] out = body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, out.length);
			try (java.io.OutputStream os = exchange.getResponseBody()) {
				os.write(out);
			}
		});
		server.start();

		try {
			NominatimGeocodingProvider provider = new NominatimGeocodingProvider(
					"http://127.0.0.1:" + server.getAddress().getPort() + "/search",
					"ISKCON-KMS-test (hermetic)",
					"in");

			// A reply shaped the way Nominatim actually shapes one.
			body.set("[{\"lat\":\"12.9716\",\"lon\":\"77.5946\","
					+ "\"display_name\":\"ISKCON Temple, Hare Krishna Hill, Bengaluru, India\"}]");
			assertThat(provider.describe("hare krishna hill"))
					.contains(new GeocodingProvider.Located(
							new GeocodingProvider.Coordinates(12.9716, 77.5946),
							"ISKCON Temple, Hare Krishna Hill, Bengaluru, India"));

			// No display_name at all: a hit with nothing to show, never an exception and never a miss.
			// The coordinates are right and the screen falls back to showing them.
			body.set("[{\"lat\":\"12.2958\",\"lon\":\"76.6394\"}]");
			Optional<GeocodingProvider.Located> unlabelled = provider.describe("mysuru");
			assertThat(unlabelled).isPresent();
			assertThat(unlabelled.get().at())
					.isEqualTo(new GeocodingProvider.Coordinates(12.2958, 76.6394));
			assertThat(unlabelled.get().resolvedAddress()).isNull();

			// And locate() still answers, unchanged, from the same lookup — the two must never be
			// able to disagree about where a place is.
			assertThat(provider.locate("mysuru"))
					.contains(new GeocodingProvider.Coordinates(12.2958, 76.6394));

			body.set("[]");
			assertThat(provider.describe("nowhere at all")).isEmpty();

		} finally {
			server.stop(0);
		}
	}

	// ---- Who may ask ------------------------------------------------------

	@Test
	@DisplayName("a temple admin cannot geocode — this is the operator's provisioning screen")
	void templeAdminIsRefused() throws Exception {
		// D-13: a temple's profile, and everything that fills it in, is the platform operator's.
		stubVerifier.reset();
		signInAsTempleAdmin();

		geocode("Bengaluru, Karnataka").andExpect(status().isForbidden());
		assertThat(geocoder.asked())
				.as("a refused caller must not have spent a lookup on the way to being refused")
				.isEmpty();
	}

	@Test
	@DisplayName("an unauthenticated caller cannot geocode")
	void anonymousIsRefused() throws Exception {
		mvc.perform(get("/api/v1/geocode").param("address", "Bengaluru, Karnataka"))
				.andExpect(status().isUnauthorized());
		assertThat(geocoder.asked()).isEmpty();
	}

	// -----------------------------------------------------------------------

	private ResultActions geocode(String address) throws Exception {
		return mvc.perform(get("/api/v1/geocode")
				.param("address", address)
				.header("Authorization", "Bearer valid-token"));
	}

	private void signInAsSuperAdmin() {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, 'uid-super', 'Platform Operator', 'super@example.com',
						'+919000000001', 'SUPER_ADMIN', 'ACTIVE')
				""");
		stubVerifier.accept("uid-super");
	}

	private void signInAsTempleAdmin() {
		UUID tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-temple-admin', 'Temple Administrator', 'temple-admin@example.com',
						'+919000000002', 'TEMPLE_ADMIN', 'ACTIVE')
				""", tenant);
		stubVerifier.accept("uid-temple-admin");
	}

	/** Four bytes standing in for a PNG. Nothing here decodes it; it only has to survive base64. */
	private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

	@TestConfiguration
	static class Stubs {

		@Bean
		@Primary
		StubTokenVerifier stubTokenVerifier() {
			return new StubTokenVerifier();
		}

		@Bean
		@Primary
		StubGeocoder stubGeocoder() {
			return new StubGeocoder();
		}

		@Bean
		@Primary
		StubStaticMap stubStaticMap() {
			return new StubStaticMap();
		}
	}

	/**
	 * A map with one place on it, and a count of how often it was asked.
	 *
	 * <p>The suite must not call OpenStreetMap — it would make the tests depend on somebody else's
	 * uptime and their rate limit, which is the reason {@code MembershipIT} gives for stubbing the
	 * same port. The counting matters as much as the answering here: several of these tests are
	 * about the lookup <em>not</em> happening.
	 */
	static class StubGeocoder implements GeocodingProvider {

		/** What Nominatim would call the one place on this map. */
		private static final String DISPLAY_NAME =
				"ISKCON Temple, Hare Krishna Hill, Bengaluru, Karnataka, 560010, India";

		private final java.util.List<String> asked =
				java.util.Collections.synchronizedList(new java.util.ArrayList<>());
		private boolean configured = true;
		private String description = DISPLAY_NAME;

		void reset() {
			asked.clear();
			configured = true;
			description = DISPLAY_NAME;
		}

		void unconfigured() {
			configured = false;
		}

		/** A geocoder that answers with a position and no label — a hit with nothing to show. */
		void withoutADescription() {
			description = null;
		}

		java.util.List<String> asked() {
			return java.util.List.copyOf(asked);
		}

		@Override
		public boolean configured() {
			return configured;
		}

		/**
		 * Kept delegating rather than answered separately, exactly as the real Nominatim provider
		 * does it: the two must never be able to disagree about where a place is.
		 */
		@Override
		public Optional<Coordinates> locate(String place) {
			return describe(place).map(Located::at);
		}

		@Override
		public Optional<Located> describe(String place) {
			asked.add(place);
			if (!configured) {
				return Optional.empty();
			}
			return "Bengaluru, Karnataka".equalsIgnoreCase(place.trim())
					? Optional.of(new Located(new Coordinates(12.9716, 77.5946), description))
					: Optional.empty();
		}
	}

	/** A map service that can be keyed, unkeyed, or keyed and having a bad minute. */
	static class StubStaticMap implements StaticMapProvider {

		private boolean configured = true;
		private byte[] png;
		private GeocodingProvider.Coordinates centredOn;

		void reset() {
			configured = true;
			png = null;
			centredOn = null;
		}

		void unconfigured() {
			configured = false;
		}

		/** Null for a service that is reachable and answers with nothing, which is not an error. */
		void serve(byte[] bytes) {
			png = bytes;
		}

		GeocodingProvider.Coordinates centredOn() {
			return centredOn;
		}

		@Override
		public boolean configured() {
			return configured;
		}

		@Override
		public Optional<byte[]> map(GeocodingProvider.Coordinates at, int widthPx, int heightPx) {
			centredOn = at;
			return Optional.ofNullable(png);
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
