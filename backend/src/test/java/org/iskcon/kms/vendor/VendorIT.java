package org.iskcon.kms.vendor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.iskcon.kms.tenancy.TenantAwareDataSource;
import org.iskcon.kms.tenancy.TenantContext;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
		admin.execute("DELETE FROM vendor_status_changes");
		admin.execute("DELETE FROM vendor_supplies");
		admin.execute("DELETE FROM vendors");
		admin.execute("DELETE FROM audit_events");
		// Anything that moved through the stock ledger is tracked now, so the item rows exist
		// even where the test never asked for them, and they hold the ingredient down.
		admin.execute("DELETE FROM inventory_items");
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
		mvc.perform(deactivate(id, "{\"reason\":\"Closed their shop\"}")).andExpect(status().isNoContent());

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

	// ---- The reason a vendor was dropped, kept as history (review item V1) ----

	@Test
	@DisplayName("a vendor cannot be deactivated without a reason")
	void deactivationNeedsAReason() throws Exception {
		UUID id = create("{\"name\":\"No Reason\",\"phone\":\"+919812345111\"}");

		// No body at all, and a body with nothing in it, are the same mistake and say the same thing.
		mvc.perform(authed(post("/api/v1/vendors/{id}/deactivate", id)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4011"));
		mvc.perform(deactivate(id, "{\"reason\":\"   \"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4011"));

		// And the vendor is untouched — a refused deactivation is not a half-done one.
		mvc.perform(authed(get("/api/v1/vendors/{id}", id)))
				.andExpect(jsonPath("$.vendor.active").value(true))
				.andExpect(jsonPath("$.statusHistory.length()").value(0));
	}

	@Test
	@DisplayName("the reason is dated, attributed, and readable on the vendor's own page")
	void reasonIsAttributedAndReadable() throws Exception {
		UUID id = create("{\"name\":\"Sharma Traders\",\"phone\":\"+919812345222\"}");
		mvc.perform(deactivate(id, "{\"reason\":\"Short-weighed three deliveries running\"}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/vendors/{id}", id)))
				.andExpect(jsonPath("$.statusHistory.length()").value(1))
				.andExpect(jsonPath("$.statusHistory[0].reason")
						.value("Short-weighed three deliveries running"))
				.andExpect(jsonPath("$.statusHistory[0].toActive").value(false))
				.andExpect(jsonPath("$.statusHistory[0].fromActive").value(true))
				.andExpect(jsonPath("$.statusHistory[0].actorName").value("Test Person"))
				.andExpect(jsonPath("$.statusHistory[0].createdAt").isNotEmpty());
	}

	@Test
	@DisplayName("successive deactivations each leave their own record — nothing is overwritten")
	void historyAccumulates() throws Exception {
		UUID id = create("{\"name\":\"On Again Off Again\",\"phone\":\"+919812345333\"}");
		mvc.perform(deactivate(id, "{\"reason\":\"Prices went up mid-season\"}"))
				.andExpect(status().isNoContent());
		// A reason coming back is welcome, not demanded.
		mvc.perform(reactivate(id, "{\"reason\":\"Agreed the old rate again\"}"))
				.andExpect(status().isNoContent());
		mvc.perform(deactivate(id, "{\"reason\":\"Stopped answering the phone\"}"))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/vendors/{id}", id)))
				.andExpect(jsonPath("$.statusHistory.length()").value(3))
				// Most recent first, so the newest entry answers "why is this one inactive?".
				.andExpect(jsonPath("$.statusHistory[0].reason").value("Stopped answering the phone"))
				.andExpect(jsonPath("$.statusHistory[1].reason").value("Agreed the old rate again"))
				.andExpect(jsonPath("$.statusHistory[1].toActive").value(true))
				.andExpect(jsonPath("$.statusHistory[2].reason").value("Prices went up mid-season"));
	}

	@Test
	@DisplayName("bringing a vendor back needs no reason, and still leaves a record")
	void reactivationReasonIsOptional() throws Exception {
		UUID id = create("{\"name\":\"Back Again\",\"phone\":\"+919812345444\"}");
		mvc.perform(deactivate(id, "{\"reason\":\"Seasonal supplier\"}")).andExpect(status().isNoContent());
		mvc.perform(authed(post("/api/v1/vendors/{id}/reactivate", id))).andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/vendors/{id}", id)))
				.andExpect(jsonPath("$.vendor.active").value(true))
				.andExpect(jsonPath("$.statusHistory.length()").value(2))
				.andExpect(jsonPath("$.statusHistory[0].toActive").value(true))
				.andExpect(jsonPath("$.statusHistory[0].reason").doesNotExist());
	}

	@Test
	@DisplayName("the history is append-only — the application cannot edit or erase a reason")
	void historyIsAppendOnly() throws Exception {
		UUID id = create("{\"name\":\"Append Only\",\"phone\":\"+919812345555\"}");
		mvc.perform(deactivate(id, "{\"reason\":\"The real reason\"}")).andExpect(status().isNoContent());

		// Through the application's own unprivileged role, with no Java in the way: the database
		// has to refuse this on its own, exactly as it does for the stock ledger.
		asApplication(app -> {
			assertThatThrownBy(() ->
					app.update("UPDATE vendor_status_changes SET reason = 'a kinder reason'"))
					.hasStackTraceContaining("append-only");
			assertThatThrownBy(() -> app.update("DELETE FROM vendor_status_changes"))
					.hasStackTraceContaining("append-only");
		});

		mvc.perform(authed(get("/api/v1/vendors/{id}", id)))
				.andExpect(jsonPath("$.statusHistory[0].reason").value("The real reason"));
	}

	/** Runs a statement as the unprivileged application role, scoped to this test's tenant. */
	private void asApplication(java.util.function.Consumer<JdbcTemplate> work) {
		DriverManagerDataSource plain = new DriverManagerDataSource();
		plain.setUrl(POSTGRES.getJdbcUrl());
		plain.setUsername(APP_ROLE);
		plain.setPassword(APP_PASSWORD);

		TenantContext.set(tenant);
		try {
			work.accept(new JdbcTemplate(new TenantAwareDataSource(plain)));
		} finally {
			TenantContext.clear();
		}
	}

	// ---- A contract end date that warns and never acts (review item V1) ----

	@Test
	@DisplayName("a contract end date is recorded and warned about, and changes nothing else")
	void contractEndDateWarnsOnly() throws Exception {
		UUID id = create("{\"name\":\"Contract Vendor\",\"phone\":\"+919812345666\","
				+ "\"contractEndDate\":\"2020-03-12\"}");
		mvc.perform(setSupply(id, "{\"ingredientId\":\"" + rice + "\",\"preferred\":true}"))
				.andExpect(status().isNoContent());

		// Long past, so it warns…
		mvc.perform(authed(get("/api/v1/vendors/{id}", id)))
				.andExpect(jsonPath("$.vendor.contractEndDate").value("2020-03-12"))
				.andExpect(jsonPath("$.vendor.contractEndingSoon").value(true))
				// …and that is all it does. Still active, still listed, still the preferred source.
				.andExpect(jsonPath("$.vendor.active").value(true))
				.andExpect(jsonPath("$.supplies[0].preferred").value(true));
		mvc.perform(authed(get("/api/v1/vendors"))).andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	@DisplayName("a contract with years to run does not warn, and no date at all is not a warning")
	void contractEndDateFarOffDoesNotWarn() throws Exception {
		UUID far = create("{\"name\":\"Far Off\",\"phone\":\"+919812345777\","
				+ "\"contractEndDate\":\"2099-01-01\"}");
		UUID none = create("{\"name\":\"No Contract\",\"phone\":\"+919812345888\"}");

		mvc.perform(authed(get("/api/v1/vendors/{id}", far)))
				.andExpect(jsonPath("$.vendor.contractEndingSoon").value(false));
		mvc.perform(authed(get("/api/v1/vendors/{id}", none)))
				.andExpect(jsonPath("$.vendor.contractEndDate").doesNotExist())
				.andExpect(jsonPath("$.vendor.contractEndingSoon").value(false));
	}

	@Test
	@DisplayName("an edit can set and clear the contract end date")
	void contractEndDateIsEditable() throws Exception {
		UUID id = create("{\"name\":\"Editable\",\"phone\":\"+919812345999\"}");
		mvc.perform(update(id, "{\"name\":\"Editable\",\"phone\":\"+919812345999\","
				+ "\"contractEndDate\":\"2026-03-31\"}")).andExpect(status().isNoContent());
		mvc.perform(authed(get("/api/v1/vendors/{id}", id)))
				.andExpect(jsonPath("$.vendor.contractEndDate").value("2026-03-31"));

		mvc.perform(update(id, "{\"name\":\"Editable\",\"phone\":\"+919812345999\"}"))
				.andExpect(status().isNoContent());
		mvc.perform(authed(get("/api/v1/vendors/{id}", id)))
				.andExpect(jsonPath("$.vendor.contractEndDate").doesNotExist());
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

	private MockHttpServletRequestBuilder update(UUID vendorId, String json) {
		return authed(put("/api/v1/vendors/{id}", vendorId))
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder deactivate(UUID vendorId, String json) {
		return authed(post("/api/v1/vendors/{id}/deactivate", vendorId))
				.contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private MockHttpServletRequestBuilder reactivate(UUID vendorId, String json) {
		return authed(post("/api/v1/vendors/{id}/reactivate", vendorId))
				.contentType(MediaType.APPLICATION_JSON).content(json);
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
