package org.iskcon.kms.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A temple cannot be added at latitude 0 and longitude 0 together (T-176).
 *
 * <p>Add a temple sent a blank coordinate box as 0, and nothing on the server refused it, so a
 * temple could be saved in the Atlantic with a calendar worked out from there — and, because D-17
 * freezes coordinates on the edit path, left there until somebody deleted it.
 *
 * <p>Every test goes through the real endpoint as a Super Admin, and each refusal also counts the
 * temple rows, because a 400 over a row that was written anyway would be the worst of both.
 *
 * <p>The bodies are written as raw JSON rather than a map, so {@code 0.000000} reaches the server
 * as exactly those characters and not as whatever a serialiser makes of a number.
 *
 * <p>Declares no stub configuration of its own, so it shares the suite's context; the token verifier
 * comes from {@code AbstractIntegrationTest}, as every class's does since T-189.
 */
class ProvisioningAtZeroZeroIT extends AbstractIntegrationTest {

	private static final String AT_ZERO_ZERO = "That puts the temple at 0, 0. Choose its real place.";

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private ObjectMapper json;

	@LocalServerPort
	private int port;

	private JdbcTemplate admin;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		signInAsSuperAdmin();
	}

	@AfterEach
	void tearDown() {
		// The same order as TenantProvisioningIT, for the same reason: the audit trail holds its
		// subjects down, and a provisioned temple seeds rows that hold the tenant down.
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM staff_schedule_template");
		admin.execute("DELETE FROM staff_profiles");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM occasions");
		admin.execute("DELETE FROM meal_kinds");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// -----------------------------------------------------------------------------------------
	// The pair is refused.
	// -----------------------------------------------------------------------------------------

	@Test
	@DisplayName("0, 0 is refused KMS-400002, named under Latitude, and no temple is written")
	void refusesZeroZero() throws Exception {
		ResponseEntity<String> response = post(body(Map.of("latitude", "0", "longitude", "0")));

		assertRefusedAtZeroZero(response);

		// The whole answer, character for character, once — so the words quoted in T-176's proof
		// are the words this test holds, not a reconstruction of them.
		assertThat(response.getBody()).isEqualTo("""
				{"code":"KMS-400002","message":"Those coordinates don't look right.",\
				"action":"Choose the temple's place, or type its real latitude and longitude.",\
				"fieldErrors":[{"field":"latitude","message":"That puts the temple at 0, 0. Choose its real place."}]}""");
	}

	@Test
	@DisplayName("0.000000, 0.000000 is the same place and is refused the same way")
	void refusesZeroZeroWrittenWithScale() throws Exception {
		// The shape the column stores a coordinate in. BigDecimal.equals would call this different
		// from 0, which is exactly the comparison the validator must not make.
		ResponseEntity<String> response = post(body(Map.of("latitude", "0.000000", "longitude", "0.000000")));

		assertRefusedAtZeroZero(response);
	}

	// -----------------------------------------------------------------------------------------
	// One axis at 0 is a real place.
	// -----------------------------------------------------------------------------------------

	@Test
	@DisplayName("latitude 0 with a real longitude is a place on the equator, and provisions")
	void equatorProvisions() {
		ResponseEntity<String> response = post(body(Map.of("latitude", "0", "longitude", "77.55")));

		assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
		assertThat(templeRows()).isEqualTo(1);
	}

	@Test
	@DisplayName("longitude 0 with a real latitude is a place on the Greenwich meridian, and provisions")
	void greenwichProvisions() {
		ResponseEntity<String> response = post(body(Map.of("latitude", "51.4769", "longitude", "0")));

		assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
		assertThat(templeRows()).isEqualTo(1);
	}

	// -----------------------------------------------------------------------------------------
	// A missing coordinate keeps its own answer.
	// -----------------------------------------------------------------------------------------

	@Test
	@DisplayName("a latitude left out entirely is still 'Enter the temple's latitude.', not 0, 0")
	void missingLatitudeKeepsItsAnswer() throws Exception {
		// Longitude 0 beside it, deliberately: the 0,0 rule must stay out of a pair it cannot see.
		Map<String, String> tokens = new LinkedHashMap<>(Map.of("longitude", "0"));
		tokens.put("latitude", null);

		assertMissingLatitude(post(body(tokens)));
	}

	@Test
	@DisplayName("a latitude sent as null is still 'Enter the temple's latitude.'")
	void nullLatitudeKeepsItsAnswer() throws Exception {
		assertMissingLatitude(post(body(Map.of("latitude", "null", "longitude", "0"))));
	}

	@Test
	@DisplayName("a latitude sent as an empty string is still 'Enter the temple's latitude.'")
	void blankLatitudeKeepsItsAnswer() throws Exception {
		assertMissingLatitude(post(body(Map.of("latitude", "\"\"", "longitude", "0"))));
	}

	// -----------------------------------------------------------------------------------------
	// Alongside something else wrong, the whole form is listed.
	// -----------------------------------------------------------------------------------------

	@Test
	@DisplayName("0, 0 with a blank name is KMS-400001, listing both")
	void zeroZeroWithABlankName() throws Exception {
		ResponseEntity<String> response = post(body(Map.of(
				"latitude", "0", "longitude", "0", "name", "\"\"")));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		JsonNode answer = json.readTree(response.getBody());
		assertThat(answer.get("code").asText())
				.as("a code about coordinates would hide the empty name box")
				.isEqualTo("KMS-400001");
		assertThat(fieldErrors(answer))
				.containsEntry("latitude", List.of(AT_ZERO_ZERO))
				.containsEntry("name", List.of("Enter the temple's name."))
				.doesNotContainKey("longitude");
		assertThat(templeRows()).isZero();
	}

	@Test
	@DisplayName("0, 0 with a malformed phone is KMS-400001, not either narrow code, listing both")
	void zeroZeroWithAMalformedPhone() throws Exception {
		// The two carve-outs each need every error to be their own kind, so neither may claim this.
		ResponseEntity<String> response = post(body(Map.of(
				"latitude", "0", "longitude", "0", "adminPhone", "\"98765\"")));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		JsonNode answer = json.readTree(response.getBody());
		assertThat(answer.get("code").asText()).isEqualTo("KMS-400001");
		assertThat(fieldErrors(answer))
				.containsEntry("latitude", List.of(AT_ZERO_ZERO))
				.containsKey("adminPhone");
		assertThat(templeRows()).isZero();
	}

	// ---------------------------------------------------------------------

	private void assertRefusedAtZeroZero(ResponseEntity<String> response) throws Exception {
		assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);

		JsonNode answer = json.readTree(response.getBody());
		assertThat(answer.get("code").asText()).isEqualTo("KMS-400002");
		assertThat(answer.get("message").asText()).isEqualTo("Those coordinates don't look right.");
		assertThat(answer.get("action").asText())
				.isEqualTo("Choose the temple's place, or type its real latitude and longitude.");

		// Exactly one entry, under latitude. Checked as the whole map, so a second copy of the
		// sentence under longitude — or a stray object error — would fail here.
		assertThat(fieldErrors(answer)).isEqualTo(Map.of("latitude", List.of(AT_ZERO_ZERO)));

		assertThat(templeRows()).as("a refused temple must not have been written").isZero();
	}

	private void assertMissingLatitude(ResponseEntity<String> response) throws Exception {
		assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
		JsonNode answer = json.readTree(response.getBody());
		assertThat(answer.get("code").asText()).isEqualTo("KMS-400001");
		assertThat(fieldErrors(answer)).isEqualTo(Map.of("latitude", List.of("Enter the temple's latitude.")));
		assertThat(templeRows()).isZero();
	}

	/** field → every message under it, so a duplicate would show as a list of two. */
	private Map<String, List<String>> fieldErrors(JsonNode answer) {
		List<JsonNode> entries = new ArrayList<>();
		answer.get("fieldErrors").forEach(entries::add);
		return entries.stream().collect(Collectors.groupingBy(
				entry -> entry.get("field").asText(),
				Collectors.mapping(entry -> entry.get("message").asText(), Collectors.toList())));
	}

	private Integer templeRows() {
		return admin.queryForObject("SELECT count(*) FROM tenants", Integer.class);
	}

	/**
	 * A valid provisioning body as raw JSON tokens, with overrides. A token of {@code null} (the
	 * Java value, not the JSON word) leaves the key out of the body entirely.
	 */
	private String body(Map<String, String> overrides) {
		Map<String, String> tokens = new LinkedHashMap<>();
		tokens.put("name", "\"Sri Sri Radha Govinda Temple\"");
		tokens.put("slug", "\"radha-govinda\"");
		tokens.put("address", "\"Bengaluru, Karnataka\"");
		tokens.put("latitude", "12.9716");
		tokens.put("longitude", "77.5946");
		tokens.put("timezone", "\"Asia/Kolkata\"");
		tokens.put("currency", "\"INR\"");
		tokens.put("is80gApproved", "true");
		tokens.put("adminName", "\"Karuna Murthy Das\"");
		tokens.put("adminEmail", "\"admin@example.com\"");
		tokens.put("adminPhone", "\"+919876543210\"");
		overrides.forEach((key, token) -> {
			if (token == null) {
				tokens.remove(key);
			}
			else {
				tokens.put(key, token);
			}
		});
		return tokens.entrySet().stream()
				.map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue())
				.collect(Collectors.joining(",", "{", "}"));
	}

	private void signInAsSuperAdmin() {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, 'uid-super', 'Platform Operator', 'super@example.com',
						'+919000000001', 'SUPER_ADMIN', 'ACTIVE')
				""");
		stubVerifier.accept("uid-super");
	}

	private ResponseEntity<String> post(String body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth("valid-token");
		return rest.postForEntity(
				"http://localhost:" + port + "/api/v1/tenants", new HttpEntity<>(body, headers), String.class);
	}
}
