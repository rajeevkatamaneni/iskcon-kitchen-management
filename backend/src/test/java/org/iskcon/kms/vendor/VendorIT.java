package org.iskcon.kms.vendor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Vendor management (E5-S1): CRUD with supply mapping, phone validation, deactivation that hides but
 * preserves, and exclusive preferred-vendor-per-ingredient.
 */
@AutoConfigureMockMvc
@Import(VendorIT.StubVerifierConfiguration.class)
class VendorIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID rice;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		insertUser("uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		insertUser("uid-vol-a", "vol-a@example.com", "VOLUNTEER");
		rice = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Rice', 'Grains', 'KG') RETURNING id
				""", UUID.class, tenant);
		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a vendor is created with a supplied ingredient and appears with it")
	void createsWithSupply() throws Exception {
		UUID id = create("{\"name\":\"Govind Wholesale\",\"phone\":\"+919812345678\"}");
		mvc.perform(setSupply(id, "{\"ingredientId\":\"" + rice + "\",\"lastPrice\":58,\"preferred\":true}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/vendors/{id}", id)))
				.andExpect(jsonPath("$.vendor.name").value("Govind Wholesale"))
				.andExpect(jsonPath("$.supplies[0].ingredientName").value("Rice"))
				.andExpect(jsonPath("$.supplies[0].preferred").value(true));
	}

	@Test
	@DisplayName("an invalid phone is rejected at entry")
	void invalidPhoneRejected() throws Exception {
		mvc.perform(createRequest("{\"name\":\"Bad Phone\",\"phone\":\"98765\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4001"));
	}

	@Test
	@DisplayName("a deactivated vendor vanishes from the default list but history is preserved")
	void deactivationHides() throws Exception {
		UUID id = create("{\"name\":\"Old Vendor\",\"phone\":\"+919812345000\"}");
		mvc.perform(authed(post("/api/v1/vendors/{id}/deactivate", id))).andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/vendors"))).andExpect(jsonPath("$.length()").value(0));
		mvc.perform(authed(get("/api/v1/vendors")).param("includeInactive", "true"))
				.andExpect(jsonPath("$.length()").value(1));
		// The record still resolves (old POs render).
		mvc.perform(authed(get("/api/v1/vendors/{id}", id)))
				.andExpect(jsonPath("$.vendor.active").value(false));
	}

	@Test
	@DisplayName("preferred vendor per ingredient is exclusive — a new preference clears the old")
	void preferredIsExclusive() throws Exception {
		UUID a = create("{\"name\":\"Vendor A\",\"phone\":\"+919800000001\"}");
		UUID b = create("{\"name\":\"Vendor B\",\"phone\":\"+919800000002\"}");
		mvc.perform(setSupply(a, "{\"ingredientId\":\"" + rice + "\",\"preferred\":true}")).andExpect(status().isNoContent());
		mvc.perform(setSupply(b, "{\"ingredientId\":\"" + rice + "\",\"preferred\":true}")).andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/vendors/{id}", a)))
				.andExpect(jsonPath("$.supplies[0].preferred").value(false));
		mvc.perform(authed(get("/api/v1/vendors/{id}", b)))
				.andExpect(jsonPath("$.supplies[0].preferred").value(true));
	}

	@Test
	@DisplayName("a duplicate vendor name is refused")
	void duplicateNameRefused() throws Exception {
		create("{\"name\":\"Govind Wholesale\",\"phone\":\"+919812345678\"}");
		mvc.perform(createRequest("{\"name\":\"govind wholesale\",\"phone\":\"+919812340000\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4918"));
	}

	@Test
	@DisplayName("a volunteer cannot manage vendors")
	void volunteerForbidden() throws Exception {
		signIn("uid-vol-a");
		mvc.perform(authed(get("/api/v1/vendors"))).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private UUID create(String json) throws Exception {
		String body = mvc.perform(createRequest(json)).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(body.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
	}

	private MockHttpServletRequestBuilder createRequest(String json) {
		return authed(post("/api/v1/vendors")).contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder setSupply(UUID vendorId, String json) {
		return authed(put("/api/v1/vendors/{id}/supplies", vendorId))
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private void insertUser(String uid, String email, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				""", tenant, uid, email, role);
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
