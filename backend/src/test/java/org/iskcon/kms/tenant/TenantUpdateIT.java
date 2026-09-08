package org.iskcon.kms.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Correcting a temple after it has been provisioned (T-008, narrowed by D-17).
 *
 * <p>Driven through MockMvc rather than TestRestTemplate because the JDK's default HTTP client
 * cannot issue a PATCH, which is why every PATCH test in this suite is written this way. (That fact
 * used to be cited from {@code RoleChangeIT}, which T-040 deleted; it was written as {@code} rather
 * than {@link}, so nothing would ever have caught the dangling reference.)
 *
 * <p><strong>What this file used to be about, and what it is about now.</strong> Its headline test
 * asserted that changing a temple's timezone re-queued its calendar precompute, because
 * {@code calendar_days} is computed per tenant from that column and a corrected zone with an
 * uncorrected calendar leaves every tithi, Ekadashi and sunrise worked out against the wrong zone.
 * D-17 froze the timezone instead, which removes the rebuild and the reason for it together, so
 * that test now asserted something that must not happen and is gone with the code it covered.
 *
 * <p>In its place, the pair of tests this file now exists for. First, that a changed
 * {@code latitude}, {@code longitude}, {@code timezone} or {@code currency} is <em>refused</em> —
 * without that, deleting the rebuild would leave an operator able to hand-{@code PATCH} a new zone
 * and silently invalidate a whole calendar. Second, and easier to get wrong, that the record saved
 * back <em>unchanged</em> still goes through: {@code latitude} is {@code NUMERIC(9,6)} so the row
 * holds {@code 12.971600} while the screen sends {@code 12.9716}, and a freeze written with
 * {@link java.math.BigDecimal#equals} would call those two different and make the screen
 * unsaveable. Every body in this file is built from {@link #validUpdate()}, whose latitude is
 * deliberately at a shorter scale than the column's, so the whole file leans on that being handled.
 */
@AutoConfigureMockMvc
@Import(TenantUpdateIT.StubVerifierConfiguration.class)
class TenantUpdateIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private ObjectMapper json;

	private JdbcTemplate admin;

	private UUID temple;

	/** A second temple, so "this temple's row" is a claim about the UPDATE's WHERE and not a shape. */
	private UUID other;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();

		temple = insertTenant();
		other = insertOtherTenant();
		signInAsSuperAdmin();
	}

	@AfterEach
	void tearDown() {
		// Same order as TenantProvisioningIT's: audit_events references users and tenants with
		// ON DELETE RESTRICT — it is a trail, and a trail must not lose its subject — and the
		// provisioning test below leaves behind everything a new temple is seeded with.
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

	// ---- The three fields that may change ------------------------------

	@Test
	@DisplayName("name, address and 80G approval are all actually written")
	void correctsEveryEditableField() throws Exception {
		// Asserts every editable column rather than a sample, for the reason TenantProvisioningIT
		// gives about its own insert: a column quietly missing from the UPDATE's SET list is far
		// worse than one that fails, because nothing would ever reveal it.
		Map<String, Object> body = validUpdate();
		body.put("name", "Sri Sri Radha Gopinatha Temple");
		body.put("address", "Mysuru, Karnataka");
		body.put("is80gApproved", true);

		patchTemple(temple, body).andExpect(status().isNoContent());

		Map<String, Object> row = admin.queryForMap("SELECT * FROM tenants WHERE id = ?", temple);
		assertThat(row.get("name")).isEqualTo("Sri Sri Radha Gopinatha Temple");
		assertThat(row.get("address")).isEqualTo("Mysuru, Karnataka");
		assertThat(row.get("is_80g_approved")).isEqualTo(true);

		// The four frozen columns are still in the SET list, written back as they were. That they
		// come out the other side unchanged is the whole point of writing them at all.
		assertThat(new java.math.BigDecimal(row.get("latitude").toString()))
				.isEqualByComparingTo("12.9716");
		assertThat(new java.math.BigDecimal(row.get("longitude").toString()))
				.isEqualByComparingTo("77.5946");
		assertThat(row.get("timezone")).isEqualTo("Asia/Kolkata");
		assertThat(row.get("currency")).isEqualTo("INR");

		// And one temple's row, not every temple's. A WHERE clause lost from that UPDATE would
		// rename every temple on the platform and no other assertion here would notice.
		assertThat(nameOf(other)).isEqualTo("Another Temple");
	}

	@Test
	@DisplayName("an address cleared on the screen is stored as nothing, not as an empty string")
	void clearingTheAddressStoresNull() throws Exception {
		Map<String, Object> body = validUpdate();
		body.put("address", "   ");

		patchTemple(temple, body).andExpect(status().isNoContent());

		assertThat(admin.queryForObject(
				"SELECT address FROM tenants WHERE id = ?", String.class, temple))
				.as("a cleared address reads as — on the screen; an empty string would read as blank")
				.isNull();
	}

	// ---- The four fields that may not (D-17) ----------------------------

	@Test
	@DisplayName("the whole record saved back unchanged goes through, scale and all")
	void savingTheRecordBackUnchangedSucceeds() throws Exception {
		// The trap the freeze is most likely to fail on, and it fails looking like a bug in the
		// screen rather than in the check. latitude is NUMERIC(9,6): the row holds 12.971600, the
		// screen sends back the 12.9716 it was given, and BigDecimal.equals — which compares scale
		// as well as value — calls those two different. A freeze written with equals refuses an
		// operator their own untouched coordinates and nothing on this screen can ever be saved.
		// Sent here at a third scale again, so the assertion is about the value and not about two
		// renderings happening to match.
		Map<String, Object> body = validUpdate();
		body.put("latitude", new java.math.BigDecimal("12.97160000"));
		body.put("longitude", new java.math.BigDecimal("77.59460"));

		patchTemple(temple, body).andExpect(status().isNoContent());

		assertThat(new java.math.BigDecimal(admin
				.queryForObject("SELECT latitude FROM tenants WHERE id = ?", String.class, temple)))
				.isEqualByComparingTo("12.9716");
	}

	@Test
	@DisplayName("a changed timezone is refused, and says so on the field")
	void refusesAChangedTimezone() throws Exception {
		// D-17: a temple that cannot move cannot change timezone either. This refusal is also what
		// makes deleting the calendar rebuild safe — calendar_days is precomputed per tenant from
		// this column, so a zone that could still be changed by hand would leave every tithi,
		// Ekadashi and sunrise computed against the old one, with nothing anywhere saying so.
		Map<String, Object> body = validUpdate();
		body.put("timezone", "Asia/Dubai");

		patchTemple(temple, body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("timezone"))
				.andExpect(jsonPath("$.fieldErrors[0].message")
						.value(org.hamcrest.Matchers.containsString("can't be changed")));

		assertThat(timezoneOf(temple)).isEqualTo("Asia/Kolkata");
	}

	@Test
	@DisplayName("a changed latitude is refused — the building does not move")
	void refusesAChangedLatitude() throws Exception {
		Map<String, Object> body = validUpdate();
		body.put("latitude", 12.2958);

		patchTemple(temple, body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("latitude"))
				.andExpect(jsonPath("$.fieldErrors[0].message")
						.value(org.hamcrest.Matchers.containsString("can't be changed")));

		assertThat(new java.math.BigDecimal(admin
				.queryForObject("SELECT latitude FROM tenants WHERE id = ?", String.class, temple)))
				.isEqualByComparingTo("12.9716");
		assertThat(nameOf(temple))
				.as("a refused request writes nothing at all, not the fields it happened to like")
				.isEqualTo("Sri Sri Radha Govinda Temple");
	}

	@Test
	@DisplayName("a changed currency is refused — money already recorded was recorded in the old one")
	void refusesAChangedCurrency() throws Exception {
		// Changing this converts nothing. Every invoice, payment and donation already stored would
		// simply start being displayed in a currency it was never in.
		Map<String, Object> body = validUpdate();
		body.put("currency", "USD");

		patchTemple(temple, body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("currency"))
				.andExpect(jsonPath("$.fieldErrors[0].message")
						.value(org.hamcrest.Matchers.containsString("can't be changed")));

		assertThat(admin.queryForObject(
				"SELECT currency FROM tenants WHERE id = ?", String.class, temple))
				.isEqualTo("INR");
	}

	@Test
	@DisplayName("a stale record changing several frozen fields is told about all of them at once")
	void refusesEveryFrozenFieldItWasSent() throws Exception {
		// A caller sending a record built from a temple as it was some time ago should not have to
		// discover the four in turn, one save at a time.
		Map<String, Object> body = validUpdate();
		body.put("latitude", 19.0760);
		body.put("longitude", 72.8777);
		body.put("timezone", "Asia/Dubai");
		body.put("currency", "USD");

		patchTemple(temple, body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.length()").value(4))
				.andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder(
						"latitude", "longitude", "timezone", "currency")));
	}

	@Test
	@DisplayName("an unusable timezone is refused rather than silently accepted")
	void refusesAnUnusableTimezone() throws Exception {
		// Still checked ahead of the freeze, so a zone that is not a zone at all is refused for what
		// it is rather than for differing from a value it could never have matched.
		Map<String, Object> body = validUpdate();
		body.put("timezone", "Asia/Bengaluru");

		patchTemple(temple, body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"));

		assertThat(timezoneOf(temple)).isEqualTo("Asia/Kolkata");
	}

	// ---- 80G ------------------------------------------------------------

	@Test
	@DisplayName("80G approval recorded after provisioning reaches the screen that reads it")
	void records80gApprovalAfterProvisioning() throws Exception {
		// The whole of docket A2: is_80g_approved was written by the provisioning insert and by
		// nothing else ever again, so a temple whose approval came through afterwards — which is
		// how 80G actually arrives — could never have it recorded, and its receipts stayed wrong.
		// Deliberately goes through the real provisioning endpoint rather than an INSERT, because
		// "after provisioning" is the whole claim.
		mvc.perform(post("/api/v1/tenants")
						.header("Authorization", "Bearer valid-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(provisionRequestWithout80g())))
				.andExpect(status().isCreated());

		UUID provisioned = admin.queryForObject(
				"SELECT id FROM tenants WHERE slug = 'radha-gopinatha'", UUID.class);
		assertThat(admin.queryForObject(
				"SELECT is_80g_approved FROM tenants WHERE id = ?", Boolean.class, provisioned))
				.as("it was provisioned without approval, which is the case that had no remedy")
				.isFalse();

		// Built from the temple that was just provisioned rather than from validUpdate(), which
		// describes a different temple: since D-17 a coordinate, zone or currency that disagrees
		// with the stored row is refused, and this body must differ in the 80G flag alone.
		Map<String, Object> body = new LinkedHashMap<>(provisionRequestWithout80g());
		body.remove("slug");
		body.remove("adminName");
		body.remove("adminEmail");
		body.remove("adminPhone");
		body.put("is80gApproved", true);
		patchTemple(provisioned, body).andExpect(status().isNoContent());

		assertThat(admin.queryForObject(
				"SELECT is_80g_approved FROM tenants WHERE id = ?", Boolean.class, provisioned))
				.isTrue();

		// And it is read back where it matters. The giving screen and the receipt path both resolve
		// the flag from this row for the current tenant (GivingPageController, and
		// MonetaryDonationService.tenantIs80gApproved), so asserting the column alone would not
		// show that a donor is now offered the PAN capture a tax certificate needs.
		signInAsTempleAdminOf(provisioned);
		mvc.perform(get("/api/v1/donations/page").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.is80gApproved").value(true));
	}

	// ---- The slug ------------------------------------------------------

	@Test
	@DisplayName("a slug sent with the correction is refused, not quietly dropped")
	void refusesASlugChange() throws Exception {
		// slug is updatable=false on the entity and is the temple's permanent identity. Jackson
		// would discard the property silently — Spring Boot leaves FAIL_ON_UNKNOWN_PROPERTIES off —
		// and a caller told the save succeeded while the web address stayed put is worse served
		// than one told plainly that it cannot change.
		Map<String, Object> body = validUpdate();
		body.put("slug", "radha-gopinatha");

		patchTemple(temple, body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("slug"))
				.andExpect(jsonPath("$.fieldErrors[0].message")
						.value(org.hamcrest.Matchers.containsString("can't be changed")));

		assertThat(admin.queryForObject("SELECT slug FROM tenants WHERE id = ?", String.class, temple))
				.isEqualTo("radha-govinda");
		assertThat(nameOf(temple))
				.as("a refused request writes nothing at all, not the fields it happened to like")
				.isEqualTo("Sri Sri Radha Govinda Temple");
	}

	// ---- The record of it ----------------------------------------------

	@Test
	@DisplayName("the correction is audited on the temple's own log, with before and after")
	void writesAnAuditEvent() throws Exception {
		Map<String, Object> body = validUpdate();
		body.put("name", "Sri Sri Radha Govinda Mandir");
		body.put("is80gApproved", true);

		patchTemple(temple, body).andExpect(status().isNoContent());

		Map<String, Object> event = admin.queryForMap("""
				SELECT ae.action, ae.entity_type, ae.entity_id, ae.before_state, ae.after_state,
					   ae.reason, u.role AS actor_role, ae.tenant_id
				FROM audit_events ae
				JOIN users u ON u.id = ae.actor_user_id
				WHERE ae.entity_type = 'TENANT' AND ae.entity_id = ?
				""", temple);

		// Its own action, not SETTINGS_UPDATED. A temple admin changing its payment credentials and
		// an operator changing a temple's legal 80G status are different acts, and one filed under
		// the other's name is invisible to anybody not already looking for it.
		assertThat(event.get("action")).isEqualTo("TENANT_UPDATED");

		assertThat(event.get("tenant_id"))
				.as("the event belongs to the temple whose record changed, as provisioning's does")
				.hasToString(temple.toString());
		assertThat(event.get("actor_role"))
				.as("the actor is the platform operator, even though the event is the temple's")
				.isEqualTo("SUPER_ADMIN");
		assertThat(event.get("before_state").toString())
				.contains("Sri Sri Radha Govinda Temple")
				.contains("\"is80gApproved\": false");
		assertThat(event.get("after_state").toString())
				.contains("Sri Sri Radha Govinda Mandir")
				.contains("\"is80gApproved\": true");
		assertThat(event.get("reason").toString()).contains("operator");

		// Both halves are the database's own rendering of the row, so a field nobody touched reads
		// identically on each. Building the after-state from the request instead made every event
		// claim the temple had moved: latitude is NUMERIC(9,6), so the stored value renders as
		// "12.971600" and the request's BigDecimal as "12.9716".
		assertThat(event.get("after_state").toString())
				.as("a coordinate nobody edited must not appear to have changed")
				.contains("\"latitude\": \"12.971600\"");
	}

	// ---- Who may do it --------------------------------------------------

	@Test
	@DisplayName("a temple admin cannot correct their own temple's profile")
	void templeAdminCannotCorrectTheProfile() throws Exception {
		// D-13, ruled 2026-09-07: operator only, against a recommendation to split the fields. The
		// cost is deliberate — a temple cannot fix its own address — and this is where it is held.
		stubVerifier.reset();
		signInAsTempleAdminOf(temple);

		patchTemple(temple, validUpdate()).andExpect(status().isForbidden());
		assertThat(nameOf(temple)).isEqualTo("Sri Sri Radha Govinda Temple");
	}

	@Test
	@DisplayName("an unauthenticated caller cannot correct a temple")
	void anonymousCannotCorrectATemple() throws Exception {
		mvc.perform(patch("/api/v1/tenants/{id}", temple)
						.contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(validUpdate())))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("correcting a temple that does not exist says so, with a code that can be quoted")
	void unknownTempleIsNotFound() throws Exception {
		patchTemple(UUID.randomUUID(), validUpdate())
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-400029"));
	}

	@Test
	@DisplayName("the detail endpoint carries the coordinates the correction screen opens on")
	void detailCarriesCoordinates() throws Exception {
		// Without these the edit screen would have to make an operator retype coordinates they did
		// not come to change, or send zeroes for them and move the temple's sunrise to the Atlantic.
		mvc.perform(get("/api/v1/tenants/{id}", temple).header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				// Asserted on the raw body rather than through a typed jsonPath: the column is
				// NUMERIC(9,6), so the value on the wire is 12.971600 and a match against a double
				// would be testing JsonPath's number coercion rather than the endpoint.
				.andExpect(content().string(org.hamcrest.Matchers.containsString("\"latitude\":12.9716")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("\"longitude\":77.5946")));
	}

	// ---------------------------------------------------------------------

	/**
	 * The temple exactly as it stands, which is what the screen sends back when nothing changed.
	 *
	 * <p>Its latitude is written {@code 12.9716} while the column holds {@code 12.971600}, and that
	 * is deliberate: it is what the screen actually sends, and every test in this file that expects
	 * a save to succeed is therefore also a test that the freeze compares by value rather than by
	 * {@link java.math.BigDecimal#equals}, which would call those two different.
	 */
	private Map<String, Object> validUpdate() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", "Sri Sri Radha Govinda Temple");
		body.put("address", "Bengaluru, Karnataka");
		body.put("latitude", 12.9716);
		body.put("longitude", 77.5946);
		body.put("timezone", "Asia/Kolkata");
		body.put("currency", "INR");
		body.put("is80gApproved", false);
		return body;
	}

	private Map<String, Object> provisionRequestWithout80g() {
		Map<String, Object> body = new HashMap<>();
		body.put("name", "Sri Sri Radha Gopinatha Temple");
		body.put("slug", "radha-gopinatha");
		body.put("address", "Mysuru, Karnataka");
		body.put("latitude", 12.2958);
		body.put("longitude", 76.6394);
		body.put("timezone", "Asia/Kolkata");
		body.put("currency", "INR");
		body.put("is80gApproved", false);
		body.put("adminName", "Karuna Murthy Das");
		body.put("adminEmail", "gopinatha-admin@example.com");
		body.put("adminPhone", "+919876543210");
		return body;
	}

	private ResultActions patchTemple(UUID id, Map<String, Object> body) throws Exception {
		return mvc.perform(patch("/api/v1/tenants/{id}", id)
				.header("Authorization", "Bearer valid-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(body)));
	}

	private UUID insertTenant() {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, address, latitude, longitude, timezone,
					currency, is_80g_approved)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 'Bengaluru, Karnataka',
					12.9716, 77.5946, 'Asia/Kolkata', 'INR', false)
				RETURNING id
				""", UUID.class);
	}

	private UUID insertOtherTenant() {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('other-temple', 'Another Temple', 19.0760, 72.8777, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
	}

	private void signInAsSuperAdmin() {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, 'uid-super', 'Platform Operator', 'super@example.com',
						'+919000000001', 'SUPER_ADMIN', 'ACTIVE')
				""");
		stubVerifier.accept("uid-super");
	}

	private void signInAsTempleAdminOf(UUID tenantId) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-temple-admin', 'Temple Administrator', 'temple-admin@example.com',
						'+919000000002', 'TEMPLE_ADMIN', 'ACTIVE')
				""", tenantId);
		stubVerifier.accept("uid-temple-admin");
	}

	private String nameOf(UUID tenantId) {
		return admin.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tenantId);
	}

	private String timezoneOf(UUID tenantId) {
		return admin.queryForObject(
				"SELECT timezone FROM tenants WHERE id = ?", String.class, tenantId);
	}

	// ---------------------------------------------------------------------

	@TestConfiguration
	static class StubVerifierConfiguration {

		@Bean
		@Primary
		StubTokenVerifier stubTokenVerifier() {
			return new StubTokenVerifier();
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
