package org.iskcon.kms.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How many decimal places of a coordinate survive the boundary where Google's number is read
 * (T-059).
 *
 * <p><strong>What was wrong.</strong> Picking "ISKCON - Mysuru" on the temple-provisioning screen
 * filled the Latitude box with a fifteen-digit number, and the confirmation card directly above it
 * — the one that asks <em>is this the right place?</em> — printed the same string. None of that was
 * our arithmetic: the Places API answers with the shortest decimal that names its own double, and
 * for that place the tail of it is float noise from a computation that was never ours. Our reader
 * did {@code asDouble()} and passed it on.
 *
 * <p><strong>Why it was worth fixing rather than living with.</strong> D-17 makes latitude and
 * longitude read-only once the temple exists, so whatever this screen stores is what the temple
 * keeps and there is deliberately no screen that can tidy it up later. Beyond that the number's one
 * human job is to be checked by an operator, and seventeen significant digits is not a number a
 * person checks — they nod at it. The machines that read it cannot tell the difference: the sixth
 * decimal of a degree is about eleven centimetres, the Vaishnava calendar wants degrees for a
 * sunrise and the Routes call wants a street.
 *
 * <p><strong>Why the tests are here and not in {@link PlacesIT}.</strong> That file's Google-reply
 * test is the right neighbour, but it lives in a class that starts a PostgreSQL container for the
 * controller half of the same feature. Nothing in this file needs a database, a Spring context or a
 * network — an {@link HttpServer} on 127.0.0.1 serves the canned replies, which is this project's
 * standing way of reaching a provider that is {@code @ConditionalOnProperty} on a value the suite
 * never sets — so it stays a class that runs anywhere in under a second.
 *
 * <p><strong>The case most worth having.</strong> {@link #alreadyShortCoordinatesAreNotPaddedOut()}
 * is the one that separates a rounding from a formatting: {@code 12.285518000000001} becomes
 * {@code 12.285518} under either, and only a coordinate that was already short tells the two apart.
 */
class CoordinatePrecisionIT {

	/**
	 * The Places reply for ISKCON - Mysuru, digit for digit as staging answered it on 2026-09-08.
	 *
	 * <p>Those digits were read off the deployed API with a minted token rather than reconstructed
	 * from memory, because the whole question here is what Google's own JSON actually carries and a
	 * fixture invented to look noisy would prove only that our own test data is noisy.
	 */
	private static final String MYSURU_DETAILS = """
			{"id":"ChIJiskcon-mysuru",
			 "displayName":{"text":"ISKCON - Mysuru"},
			 "formattedAddress":"Jayanagara, Mysuru, Karnataka 570014, India",
			 "location":{"latitude":12.285518000000001,"longitude":76.6340866}}""";

	private HttpServer server;
	private final AtomicReference<String> body = new AtomicReference<>("{}");

	@BeforeEach
	void startTheMapService() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> respond(exchange, body.get()));
		server.start();
	}

	@AfterEach
	void stopTheMapService() {
		server.stop(0);
	}

	// ---- Places: the picker the provisioning screen uses -------------------

	@Test
	@DisplayName("a picked place arrives with six decimals, not with Google's float noise")
	void aPickedPlaceIsCutToSixDecimals() {
		body.set(MYSURU_DETAILS);

		Optional<PlaceSuggestionProvider.Place> found = places("test-key").resolve("ChIJiskcon-mysuru", null);

		assertThat(found).isPresent();
		assertThat(found.get().at()).isEqualTo(new GeocodingProvider.Coordinates(12.285518, 76.634087));
	}

	@Test
	@DisplayName("what the browser is sent is the six-decimal number, so the card and the box agree")
	void theWireCarriesTheSixDecimalNumber() throws Exception {
		// The screen prints exactly what this JSON says — the confirmation card and the Latitude box
		// are the same value rendered twice — so this is the assertion that matches what an operator
		// sees. Asserted on the serialised form rather than on the double, because the defect Rajeev
		// reported was a string on a screen.
		body.set(MYSURU_DETAILS);

		String json = new ObjectMapper()
				.writeValueAsString(places("test-key").resolve("ChIJiskcon-mysuru", null).orElseThrow());

		assertThat(json).contains("\"latitude\":12.285518").contains("\"longitude\":76.634087");
		assertThat(json).doesNotContain("12.285518000000001");
	}

	@Test
	@DisplayName("a coordinate that is already short keeps its own length and is not padded out")
	void alreadyShortCoordinatesAreNotPaddedOut() throws Exception {
		// The case that rules out a fix written as string formatting. Rounding leaves this pair
		// exactly as it arrived; a six-place format would turn a number somebody could read into one
		// that looks machine-generated, and would do it to every temple whose coordinates are honest.
		body.set("""
				{"id":"ChIJhare-krishna-hill",
				 "displayName":{"text":"ISKCON Sri Radha Krishna Temple"},
				 "formattedAddress":"Hare Krishna Hill, Chord Rd, Bengaluru, Karnataka 560010, India",
				 "location":{"latitude":12.9716,"longitude":77.5946}}""");

		PlaceSuggestionProvider.Place found =
				places("test-key").resolve("ChIJhare-krishna-hill", null).orElseThrow();

		assertThat(found.at()).isEqualTo(new GeocodingProvider.Coordinates(12.9716, 77.5946));
		assertThat(new ObjectMapper().writeValueAsString(found))
				.contains("\"latitude\":12.9716")
				.contains("\"longitude\":77.5946");
	}

	@Test
	@DisplayName("a reply this class cannot round is still an answer and never an exception")
	void anUnroundableReplyDoesNotRaise() {
		// The provider's standing promise is that a bad reply from a map service is an empty answer
		// or a plain box, never a raised exception — a map having a bad minute must not stand between
		// an operator and a temple. An exponent this size reads back as a value BigDecimal refuses,
		// so the rounding has to step aside rather than throw. The coordinate is nonsense either way
		// and the column's range check is the right place for it to be refused.
		body.set("""
				{"id":"ChIJnowhere","displayName":{"text":"Nowhere"},
				 "formattedAddress":"Nowhere, India",
				 "location":{"latitude":1e400,"longitude":1e400}}""");

		assertThatCode(() -> places("test-key").resolve("ChIJnowhere", null)).doesNotThrowAnyException();
	}

	// ---- Geocoding: the same cut, one decimal milder -----------------------

	@Test
	@DisplayName("the geocoder is cut to six too, so two providers cannot disagree about precision")
	void geocodedCoordinatesAreCutToSixDecimals() {
		// The Geocoding API is the milder case and it was checked rather than assumed: it renders
		// seven decimals, so it never produced the seventeen-digit string Places did. Seven is still
		// one more than tenants.latitude and meal_plans.delivery_latitude hold — both NUMERIC(9,6) —
		// and both providers answer with the same Coordinates record, so leaving one of them uncut
		// would mean the number a caller shows and the number the row keeps differ by which service
		// happened to answer.
		body.set("""
				{"status":"OK","results":[{
					"formatted_address":"Jayanagara, Mysuru, Karnataka 570014, India",
					"geometry":{"location":{"lat":12.2855180,"lng":76.6340866}}}]}""");

		assertThat(geocoder("test-key").locate("iskcon mysuru"))
				.contains(new GeocodingProvider.Coordinates(12.285518, 76.634087));
	}

	@Test
	@DisplayName("a geocoded coordinate that is already short is left alone as well")
	void shortGeocodedCoordinatesAreLeftAlone() {
		body.set("""
				{"status":"OK","results":[{
					"formatted_address":"ISKCON Temple, Hare Krishna Hill, Bengaluru, Karnataka, India",
					"geometry":{"location":{"lat":12.9716,"lng":77.5946}}}]}""");

		assertThat(geocoder("test-key").describe("hare krishna hill").orElseThrow().at())
				.isEqualTo(new GeocodingProvider.Coordinates(12.9716, 77.5946));
	}

	// ---- The promise belongs to the port, not to each provider (T-063) ----

	/**
	 * A provider written later, by somebody who never read any of this, that does nothing at all
	 * about precision — it reads a reply and hands the numbers straight back.
	 *
	 * <p>This is the whole acceptance criterion for T-063 in one class. Before the rounding moved
	 * into {@link GeocodingProvider.Coordinates}, the promise was kept by two private methods in two
	 * sibling classes, so a third implementation kept it only by remembering to; this one is written
	 * as carelessly as it is possible to be and the port keeps the promise over its head.
	 */
	private static final class NaiveProvider implements GeocodingProvider {

		@Override
		public Optional<GeocodingProvider.Coordinates> locate(String place) {
			// Google's own reply for ISKCON - Mysuru, digit for digit, with nothing done to it.
			return Optional.of(new GeocodingProvider.Coordinates(12.285518000000001, 76.6340866));
		}
	}

	@Test
	@DisplayName("a new provider that does nothing about precision still answers in six decimals")
	void aProviderThatDoesNothingStillKeepsThePromise() {
		assertThat(new NaiveProvider().locate("iskcon mysuru"))
				.as("the port rounds, so an implementation does not have to know that it must")
				.contains(new GeocodingProvider.Coordinates(12.285518, 76.634087));

		// And through describe(), which is the defaulted method every such provider inherits.
		assertThat(new NaiveProvider().describe("iskcon mysuru").orElseThrow().at())
				.isEqualTo(new GeocodingProvider.Coordinates(12.285518, 76.634087));
	}

	@Test
	@DisplayName("the constructor itself rounds, so there is no way to hold an unrounded coordinate")
	void theConstructorIsWhereThePromiseLives() {
		// The reason this is a compact constructor and not a static factory. A factory would leave
		// new Coordinates(...) reachable and enforce nothing: every construction site outside this
		// package — three in meal/MealPlanService and the readers in tenant/ and document/ — calls
		// the constructor directly, and so would the next one.
		GeocodingProvider.Coordinates built =
				new GeocodingProvider.Coordinates(12.285518000000001, 76.6340866);

		assertThat(built.latitude()).isEqualTo(12.285518);
		assertThat(built.longitude()).isEqualTo(76.634087);
	}

	@Test
	@DisplayName("a coordinate read back from a NUMERIC(9,6) column is unchanged by the rounding")
	void aCoordinateFromAColumnIsUntouched() {
		// The claim the ledger asked to be tested rather than assumed. tenants.latitude (V1) and
		// meal_plans.delivery_latitude (V88) are both NUMERIC(9,6), so every Coordinates built from
		// one of those columns is already at six decimals and the new constructor is a no-op for it.
		// No database is needed to show it: the question is what BigDecimal("12.971600").doubleValue()
		// survives, and that is arithmetic.
		for (String stored : new String[] {"12.971600", "77.594600", "12.285518", "76.634087",
				"-33.868820", "0.000000", "89.999999", "-179.999999"}) {
			double column = new java.math.BigDecimal(stored).doubleValue();
			assertThat(new GeocodingProvider.Coordinates(column, column).latitude())
					.as("a value the database already holds at six decimals is handed back unchanged")
					.isEqualTo(column);
		}
	}

	@Test
	@DisplayName("a coordinate that is not finite is carried, not rounded, and never raises")
	void theConstructorNeverRaises() {
		// The providers' standing promise — a bad reply from a map service is an empty answer, never
		// an exception — now has to hold inside the record they answer with. BigDecimal.valueOf
		// throws on these three, so the guard that used to sit in each provider has to move with the
		// rounding rather than be left behind.
		for (double bad : new double[] {
				Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN}) {
			assertThatCode(() -> new GeocodingProvider.Coordinates(bad, bad)).doesNotThrowAnyException();
		}
		assertThat(new GeocodingProvider.Coordinates(Double.POSITIVE_INFINITY, 0).latitude())
				.isEqualTo(Double.POSITIVE_INFINITY);
	}

	// ---- Plumbing ---------------------------------------------------------

	private GooglePlaceSuggestionProvider places(String key) {
		String base = "http://127.0.0.1:" + server.getAddress().getPort();
		return new GooglePlaceSuggestionProvider(
				base + "/v1/places:autocomplete", base + "/v1/places/", key, "in");
	}

	private GoogleGeocodingProvider geocoder(String key) {
		return new GoogleGeocodingProvider(
				"http://127.0.0.1:" + server.getAddress().getPort() + "/geocode", key, "in");
	}

	private static void respond(HttpExchange exchange, String payload) throws java.io.IOException {
		byte[] out = payload.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, out.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(out);
		}
	}
}
