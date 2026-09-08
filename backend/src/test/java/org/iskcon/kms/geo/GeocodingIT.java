package org.iskcon.kms.geo;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Geocoding an address through Google, and every way that can fail without anybody noticing.
 *
 * <p><strong>Real HTTP, no network.</strong> Each test runs the actual provider against a
 * {@link HttpServer} on 127.0.0.1 serving canned JSON, so what is exercised is the real request
 * building, the real socket, and the real parse — while the suite still runs on a train, spends
 * nobody's quota, and costs nothing. That technique was already in this file for the provider this
 * one replaces; it is kept because it is the only way to reach a provider that is
 * {@code @ConditionalOnProperty} on a value the suite deliberately never sets.
 *
 * <p><strong>Why there is no Spring context here any more.</strong> Until T-053 this file also
 * tested {@code /api/v1/geocode}, an endpoint whose only caller was the temple-provisioning screen's
 * address box. That screen now picks its address from Places, which returns coordinates with the
 * pick, so the endpoint had no caller left and went with the feature. What is left is a provider, so
 * what is left is a provider's test: no database, no MockMvc, no stubs standing in for the thing
 * under test.
 *
 * <p><strong>The unhappy paths are the point.</strong> This API answers a refused key, an exhausted
 * quota and an honest miss all with HTTP 200, so a test that only proves the happy path proves
 * almost nothing about the code that will actually run on a bad afternoon.
 */
class GeocodingIT {

	/** A reply shaped the way the Geocoding API actually shapes one. */
	private static final String BENGALURU = """
			{"status":"OK","results":[{
				"formatted_address":"ISKCON Temple, Hare Krishna Hill, Bengaluru, Karnataka 560010, India",
				"geometry":{"location":{"lat":12.9716,"lng":77.5946}}}]}
			""";

	private HttpServer server;
	private final AtomicReference<String> body = new AtomicReference<>("{\"status\":\"ZERO_RESULTS\",\"results\":[]}");
	private final AtomicInteger httpStatus = new AtomicInteger(200);
	private final List<String> asked = Collections.synchronizedList(new java.util.ArrayList<>());

	@BeforeEach
	void startTheMapService() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/geocode", exchange -> {
			// The raw query, not getQuery(): the decoded form would turn the country restriction's
			// %3A back into a colon and the assertion below would be about the wrong thing.
			asked.add(exchange.getRequestURI().getRawQuery());
			byte[] out = body.get().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(httpStatus.get(), out.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(out);
			}
		});
		server.start();
	}

	@AfterEach
	void stopTheMapService() {
		server.stop(0);
	}

	// ---- What comes back --------------------------------------------------

	@Test
	@DisplayName("an address Google knows comes back as coordinates and its own rendering of the place")
	void findsAnAddressAndHowGoogleWritesIt() {
		body.set(BENGALURU);

		assertThat(provider().describe("hare krishna hill"))
				.contains(new GeocodingProvider.Located(
						new GeocodingProvider.Coordinates(12.9716, 77.5946),
						"ISKCON Temple, Hare Krishna Hill, Bengaluru, Karnataka 560010, India"));

		// The whole of what is sent, asserted rather than assumed. The country restriction is not
		// cosmetic — without it "Bengaluru" is also a place in Texas — and a key that never reached
		// the query string would fail as a REQUEST_DENIED nobody would connect to this line.
		assertThat(asked).hasSize(1);
		assertThat(asked.get(0))
				.contains("address=hare+krishna+hill")
				.contains("components=country%3Ain")
				.contains("key=test-key");
	}

	@Test
	@DisplayName("locate() and describe() can never disagree about where a place is")
	void locateAgreesWithDescribe() {
		body.set(BENGALURU);
		GoogleGeocodingProvider provider = provider();

		assertThat(provider.locate("hare krishna hill"))
				.contains(new GeocodingProvider.Coordinates(12.9716, 77.5946));
		assertThat(provider.describe("hare krishna hill").map(GeocodingProvider.Located::at))
				.isEqualTo(provider.locate("hare krishna hill"));
	}

	@Test
	@DisplayName("a reply with coordinates and no formatted address is a hit with nothing to show")
	void anUnlabelledReplyIsStillAHit() {
		body.set("""
				{"status":"OK","results":[{"geometry":{"location":{"lat":12.2958,"lng":76.6394}}}]}
				""");

		Optional<GeocodingProvider.Located> found = provider().describe("mysuru");

		assertThat(found).isPresent();
		assertThat(found.get().at()).isEqualTo(new GeocodingProvider.Coordinates(12.2958, 76.6394));
		assertThat(found.get().resolvedAddress())
				.as("a position with no label is a hit, not a miss — the caller shows the coordinates")
				.isNull();
	}

	// ---- Every way this answers 200 and means no ---------------------------

	@Test
	@DisplayName("ZERO_RESULTS is an ordinary miss and nothing is raised")
	void reportsAMissWithoutFailing() {
		body.set("{\"status\":\"ZERO_RESULTS\",\"results\":[]}");

		assertThat(provider().describe("nowhere at all")).isEmpty();
	}

	@Test
	@DisplayName("a refused key arrives as HTTP 200 and must not become a temple in the Gulf of Guinea")
	void aTwoHundredCarryingRequestDeniedIsAMissAndNotZeroZero() {
		// The trap this API sets and the Places API does not. REQUEST_DENIED, OVER_QUERY_LIMIT and
		// an API not enabled on the project are all 200s with an empty results array, so code that
		// trusted the status line and read geometry.location straight out would get Jackson's
		// asDouble() default of 0.0 for both — which is a real place off the coast of Ghana, and
		// would be written onto a temple as where it is.
		body.set("""
				{"status":"REQUEST_DENIED","error_message":"The provided API key is invalid.","results":[]}
				""");

		assertThat(provider().describe("hare krishna hill"))
				.as("nothing is a better answer than somewhere wrong")
				.isEmpty();
	}

	@Test
	@DisplayName("OK with no location under it is refused rather than read as 0,0")
	void okWithNothingUnderItIsAMiss() {
		// Should not happen, and is checked anyway for the same reason as the test above: the cost of
		// being wrong here is a temple relocated to the middle of the Atlantic, silently.
		body.set("{\"status\":\"OK\",\"results\":[{\"formatted_address\":\"Somewhere\"}]}");

		assertThat(provider().describe("hare krishna hill")).isEmpty();
	}

	// ---- Failure is empty, never an exception ------------------------------

	@Test
	@DisplayName("a map service having a bad minute costs the lookup and nothing else")
	void aFailingServiceIsEmptyRatherThanAnException() {
		httpStatus.set(500);
		body.set("upstream is unwell");

		assertThat(provider().describe("hare krishna hill"))
				.as("a devotee registering must not see a map service's bad day")
				.isEmpty();
		assertThat(asked).hasSize(1);
	}

	@Test
	@DisplayName("a service that cannot be reached at all is empty too")
	void anUnreachableServiceIsEmpty() {
		// Nothing is listening on port 1. Connection refused rather than a reply, which is a different
		// code path from a 500 and the one a DNS failure or a severed network takes.
		GoogleGeocodingProvider unreachable =
				new GoogleGeocodingProvider("http://127.0.0.1:1/geocode", "test-key", "in");

		assertThat(unreachable.describe("hare krishna hill")).isEmpty();
		assertThat(unreachable.locate("hare krishna hill")).isEmpty();
	}

	@Test
	@DisplayName("a reply that is not JSON at all is empty, not a stack trace")
	void aMalformedReplyIsEmpty() {
		body.set("<html><body>502 Bad Gateway</body></html>");

		assertThat(provider().describe("hare krishna hill")).isEmpty();
	}

	// ---- Not asking at all -------------------------------------------------

	@Test
	@DisplayName("no key means the provider says so, and asks nobody anything")
	void withoutAKeyNothingIsAskedAndNothingIsClaimedMissing() {
		// configured() is not tidiness: MealPlanService shows KMS-400078 — "that address could not be
		// found" — only when something actually looked. Selecting google without a key must report
		// itself unconfigured, or a planner is told an address does not exist when nobody looked.
		GoogleGeocodingProvider unkeyed =
				new GoogleGeocodingProvider(endpoint(), "  ", "in");

		assertThat(unkeyed.configured()).isFalse();
		assertThat(unkeyed.describe("hare krishna hill")).isEmpty();
		assertThat(asked).as("a lookup with no key is not worth a round trip").isEmpty();
	}

	@Test
	@DisplayName("a configured provider says so, and an empty box is not a lookup")
	void blankInputIsNotALookup() {
		GoogleGeocodingProvider provider = provider();

		assertThat(provider.configured()).isTrue();
		assertThat(provider.describe("   ")).isEmpty();
		assertThat(provider.describe(null)).isEmpty();
		assertThat(asked).as("nobody is billed for an empty box").isEmpty();
	}

	// ---- Paid for once ----------------------------------------------------

	@Test
	@DisplayName("a place is looked up once and then remembered — every lookup is billed")
	void aPlaceIsLookedUpOnce() {
		body.set(BENGALURU);
		GoogleGeocodingProvider provider = provider();

		provider.describe("Hare Krishna Hill");
		provider.describe("hare krishna hill");
		provider.locate("  HARE KRISHNA HILL  ");

		assertThat(asked)
				.as("one place, one request — the second devotee from Jayanagar costs nothing")
				.hasSize(1);
	}

	@Test
	@DisplayName("a miss is remembered too, so a typo retried in a loop costs one request")
	void aMissIsRememberedAsWellAsAHit() {
		body.set("{\"status\":\"ZERO_RESULTS\",\"results\":[]}");
		GoogleGeocodingProvider provider = provider();

		assertThat(provider.describe("nowhere at all")).isEmpty();
		assertThat(provider.describe("nowhere at all")).isEmpty();

		assertThat(asked).hasSize(1);
	}

	// ---- The port itself ---------------------------------------------------

	@Test
	@DisplayName("a provider that only implements locate() still works, and describes nothing")
	void theDefaultDescriptionIsAPositionWithNoLabel() {
		// Why describe() is a *default* on the port: this is the shape the other implementations and
		// the other callers are in. NoGeocodingProvider keeps answering empty, MembershipService and
		// MealPlanService keep calling locate(), and the interface stays a functional one — so the
		// test stubs written as lambdas still compile. Written as a lambda here for exactly that
		// reason.
		GeocodingProvider onlyLocate = place -> Optional.of(new GeocodingProvider.Coordinates(1, 2));

		assertThat(onlyLocate.describe("anywhere"))
				.contains(new GeocodingProvider.Located(new GeocodingProvider.Coordinates(1, 2), null));
		assertThat(onlyLocate.describe("anywhere").map(GeocodingProvider.Located::at))
				.as("the default must agree with locate() about where the place is")
				.isEqualTo(onlyLocate.locate("anywhere"));
	}

	@Test
	@DisplayName("the default provider is no map service at all, and it never claims a miss")
	void theDefaultIsNoMapServiceAtAll() {
		// kms.geocoding.provider is 'none' in the suite and in local development, and this is what
		// answers there. It must stay reachable-by-nothing: no key, no socket, no bill.
		NoGeocodingProvider none = new NoGeocodingProvider();

		assertThat(none.configured()).isFalse();
		assertThat(none.locate("hare krishna hill")).isEmpty();
		assertThat(none.describe("hare krishna hill")).isEmpty();
	}

	// -----------------------------------------------------------------------

	private GoogleGeocodingProvider provider() {
		return new GoogleGeocodingProvider(endpoint(), "test-key", "in");
	}

	private String endpoint() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/geocode";
	}
}
