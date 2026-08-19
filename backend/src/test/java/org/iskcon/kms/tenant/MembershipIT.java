package org.iskcon.kms.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.AuthenticationFilter;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.geo.GeocodingProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Joining a temple, over real HTTP against a real database (E1-S17).
 *
 * <p>The interesting cases are the boundaries, because this is the one place where the tenant comes
 * from the request: that a person with no membership can do this and nothing else, that joining
 * gives them exactly one volunteer membership, and that holding two memberships never lets a request
 * see further than the one it is speaking for.
 */
@Import(MembershipIT.StubVerifierConfiguration.class)
class MembershipIT extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@LocalServerPort
	private int port;

	private JdbcTemplate admin;
	private UUID govinda;
	private UUID krishna;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		govinda = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		krishna = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a devotee with no temple may see the temples and join one, and nothing else")
	void joinsATemple() {
		stubVerifier.accept("uid-new", "devotee@example.com", "+919000000101");

		// Before joining they are somebody Google vouched for and nobody this product knows.
		assertThat(get("/api/v1/whoami").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(get("/api/v1/ingredients").getStatusCode())
				.as("a person with no membership must not reach a temple's data")
				.isEqualTo(HttpStatus.UNAUTHORIZED);

		ResponseEntity<String> temples = get("/api/v1/temples");
		assertThat(temples.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(temples.getBody()).contains("Radha Govinda").contains("Radha Krishna");

		assertThat(post("/api/v1/temples/" + govinda + "/join").getStatusCode())
				.isEqualTo(HttpStatus.CREATED);

		// And now they are a volunteer of that temple, without signing in again.
		ResponseEntity<String> me = get("/api/v1/whoami");
		assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(me.getBody()).contains("VOLUNTEER").contains(govinda.toString());
	}

	@Test
	@DisplayName("the new member shows up in that temple's people, and only that temple's")
	void appearsInTheTemplesUsers() {
		stubVerifier.accept("uid-new", "devotee@example.com", "+919000000101");
		post("/api/v1/temples/" + govinda + "/join");

		assertThat(admin.queryForObject("""
				SELECT count(*) FROM users WHERE firebase_uid = 'uid-new' AND tenant_id = ?
				""", Integer.class, govinda)).isEqualTo(1);
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM users WHERE firebase_uid = 'uid-new' AND tenant_id = ?
				""", Integer.class, krishna)).isZero();
		assertThat(admin.queryForObject("""
				SELECT role FROM users WHERE firebase_uid = 'uid-new'
				""", String.class)).isEqualTo("VOLUNTEER");
		assertThat(admin.queryForObject("""
				SELECT full_name FROM users WHERE firebase_uid = 'uid-new'
				""", String.class))
				.as("the temple's people list shows the name they gave, not a fragment of their email")
				.isEqualTo("Nitai Das");
	}

	@Test
	@DisplayName("joining the same temple twice changes nothing")
	void joiningTwiceIsANoOp() {
		stubVerifier.accept("uid-new", "devotee@example.com", "+919000000101");

		post("/api/v1/temples/" + govinda + "/join");
		post("/api/v1/temples/" + govinda + "/join");

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM users WHERE firebase_uid = 'uid-new'", Integer.class))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("a devotee may serve at two temples, and each request speaks for one of them")
	void twoTemplesOneRequestAtATime() {
		stubVerifier.accept("uid-both", "seva@example.com", "+919000000102");
		post("/api/v1/temples/" + govinda + "/join");
		post("/api/v1/temples/" + krishna + "/join");

		// The temple they joined first is where they land when they say nothing.
		assertThat(get("/api/v1/whoami").getBody()).contains(govinda.toString());

		// Naming the other one switches, without a second sign-in.
		assertThat(get("/api/v1/whoami", krishna.toString()).getBody()).contains(krishna.toString());

		// A temple they do not belong to is not a way in: they fall back to their own.
		assertThat(get("/api/v1/whoami", UUID.randomUUID().toString()).getBody())
				.as("the tenant is chosen from their memberships, never taken from the request")
				.contains(govinda.toString());
	}

	@Test
	@DisplayName("the nearest temples come first, and the far ones do not come at all")
	void findsTemplesNearby() {
		// Mysore is about 130 km from Bengaluru: outside any sensible "temples near me".
		UUID mysore = insertTenantAt("iskcon-mysore", "ISKCON Mysore", 12.2958, 76.6394);
		stubVerifier.accept("uid-new", "devotee@example.com", "+919000000101");

		ResponseEntity<String> near = get("/api/v1/temples?near=12.9716,77.5946&withinKm=25");
		assertThat(near.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(near.getBody())
				.as("a devotee standing in Bengaluru is offered Bengaluru")
				.contains("Radha Govinda")
				.doesNotContain("Mysore");
		assertThat(near.getBody())
				.as("how far away, so the nearest is obvious without reading the addresses")
				.contains("distanceKm");

		// Registering somewhere they are not standing: ask by name instead.
		assertThat(get("/api/v1/temples?q=mysore").getBody())
				.contains("Mysore")
				.doesNotContain("Radha Govinda");
		assertThat(mysore).isNotNull();
	}

	@Test
	@DisplayName("a place is looked up on the map, so it need not be spelled the way an address is")
	void findsTemplesNearAPlace() {
		insertTenantAt("iskcon-mysore", "ISKCON Mysore", 12.2958, 76.6394);
		stubVerifier.accept("uid-new", "devotee@example.com", "+919000000101");

		// "Mysuru" appears in no temple's name or address — only the map knows it is Mysore.
		ResponseEntity<String> byPlace = get("/api/v1/temples?q=Mysuru");

		assertThat(byPlace.getBody())
				.as("the devotee typed where they are, not what the temple is called")
				.contains("ISKCON Mysore")
				.doesNotContain("Radha Govinda");
		assertThat(byPlace.getBody()).contains("distanceKm");
	}

	@Test
	@DisplayName("a temple needs a name and a number, whichever way they signed in")
	void nameAndNumberAreRequired() {
		stubVerifier.accept("uid-new", "devotee@example.com", "+919000000101");

		// Google proves an email and nothing else. A shift cannot be staffed from an email address.
		assertThat(post("/api/v1/temples/" + govinda + "/join",
				"{\"firstName\":\"Nitai\",\"lastName\":\"Das\",\"phone\":\"\"}")
				.getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM users WHERE firebase_uid = 'uid-new'", Integer.class)).isZero();
	}

	@Test
	@DisplayName("joining a temple that does not exist is refused")
	void unknownTempleIsRefused() {
		stubVerifier.accept("uid-new", "devotee@example.com", "+919000000101");

		assertThat(post("/api/v1/temples/" + UUID.randomUUID() + "/join").getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	// ---------------------------------------------------------------------

	private ResponseEntity<String> get(String path) {
		return get(path, null);
	}

	private ResponseEntity<String> get(String path, String temple) {
		return exchange(path, HttpMethod.GET, temple);
	}

	private ResponseEntity<String> post(String path) {
		return post(path, """
				{"firstName":"Nitai","lastName":"Das","phone":"+919000000101"}
				""");
	}

	private ResponseEntity<String> post(String path, String body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth("valid-token");
		headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
		return rest.exchange("http://localhost:" + port + path, HttpMethod.POST,
				new HttpEntity<>(body, headers), String.class);
	}

	private ResponseEntity<String> exchange(String path, HttpMethod method, String temple) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth("valid-token");
		if (temple != null) {
			headers.set(AuthenticationFilter.TEMPLE_HEADER, temple);
		}
		return rest.exchange(
				"http://localhost:" + port + path, method, new HttpEntity<>(headers), String.class);
	}

	private UUID insertTenant(String slug, String name) {
		return insertTenantAt(slug, name, 12.9716, 77.5946);
	}

	private UUID insertTenantAt(String slug, String name, double latitude, double longitude) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, ?, ?, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, name, latitude, longitude);
	}

	// ---------------------------------------------------------------------

	@TestConfiguration
	static class StubVerifierConfiguration {

		@Bean
		@Primary
		StubTokenVerifier stubTokenVerifier() {
			return new StubTokenVerifier();
		}

		/**
		 * A map with one place on it. The suite must not call OpenStreetMap — it would make the tests
		 * depend on somebody else's uptime and their rate limit.
		 */
		@Bean
		@Primary
		GeocodingProvider stubGeocoding() {
			return place -> "mysuru".equalsIgnoreCase(place.trim())
					? java.util.Optional.of(new GeocodingProvider.Coordinates(12.2958, 76.6394))
					: java.util.Optional.empty();
		}
	}

	static class StubTokenVerifier implements TokenVerifier {

		private final Map<String, VerifiedSubject> accepted = new HashMap<>();

		void reset() {
			accepted.clear();
		}

		void accept(String uid, String email, String phone) {
			accepted.put("valid-token", new VerifiedSubject(uid, email, phone, true));
		}

		@Override
		public VerifiedSubject verify(String idToken) throws InvalidTokenException {
			VerifiedSubject subject = accepted.get(idToken);
			if (subject == null) {
				throw new InvalidTokenException("not accepted by the stub");
			}
			return subject;
		}
	}
}
