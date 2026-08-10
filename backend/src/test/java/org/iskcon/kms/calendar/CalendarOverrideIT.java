package org.iskcon.kms.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Admin calendar overrides (E4-S3): an override changes what consumers see immediately, survives the
 * nightly recompute, reverts cleanly, is audited, and is refused to non-admins.
 */
@AutoConfigureMockMvc
@Import(CalendarOverrideIT.StubVerifierConfiguration.class)
class CalendarOverrideIT extends AbstractIntegrationTest {

	// 10 Jan 2025 is a computed Ekadashi for Bengaluru; a good date to override off.
	private static final String EKADASHI = "2025-01-10";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private CalendarService calendarService;

	private JdbcTemplate admin;
	private UUID tenant;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		insertUser(tenant, "uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");
		insertUser(tenant, "uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		precompute();
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM calendar_overrides");
		admin.execute("DELETE FROM calendar_days");
		admin.execute("DELETE FROM calendar_precompute_state");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	private void precompute() {
		TenantContext.set(tenant);
		try {
			calendarService.precomputeForCurrentTenant(LocalDate.of(2025, 1, 1), 40);
		} finally {
			TenantContext.clear();
		}
	}

	@Test
	@DisplayName("an override changes what the calendar shows immediately, and is badged")
	void overrideChangesReadImmediately() throws Exception {
		signIn("uid-admin-a");
		// Computed: this date is an Ekadashi.
		mvc.perform(getDay(EKADASHI)).andExpect(jsonPath("$.isEkadashi").value(true))
				.andExpect(jsonPath("$.overridden").value(false));

		mvc.perform(setOverride(EKADASHI, false, "Local GBC ruling moves the fast"))
				.andExpect(status().isNoContent());

		mvc.perform(getDay(EKADASHI))
				.andExpect(jsonPath("$.isEkadashi").value(false))
				.andExpect(jsonPath("$.overridden").value(true))
				.andExpect(jsonPath("$.overrideReason").value("Local GBC ruling moves the fast"));

		assertThat(auditCount("CALENDAR_OVERRIDDEN")).isEqualTo(1);
	}

	@Test
	@DisplayName("an override survives the nightly recompute")
	void overrideSurvivesRecompute() throws Exception {
		signIn("uid-admin-a");
		mvc.perform(setOverride(EKADASHI, false, "correction")).andExpect(status().isNoContent());

		precompute(); // the nightly job re-runs

		mvc.perform(getDay(EKADASHI))
				.andExpect(jsonPath("$.isEkadashi").value(false))
				.andExpect(jsonPath("$.overridden").value(true));
	}

	@Test
	@DisplayName("reverting an override restores the computed value")
	void revertRestoresComputed() throws Exception {
		signIn("uid-admin-a");
		mvc.perform(setOverride(EKADASHI, false, "correction")).andExpect(status().isNoContent());
		mvc.perform(delete("/api/v1/calendar/{d}/override", EKADASHI)
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isNoContent());

		mvc.perform(getDay(EKADASHI))
				.andExpect(jsonPath("$.isEkadashi").value(true))
				.andExpect(jsonPath("$.overridden").value(false));
		assertThat(auditCount("CALENDAR_OVERRIDE_REVERTED")).isEqualTo(1);
	}

	@Test
	@DisplayName("an override without a reason is refused")
	void reasonRequired() throws Exception {
		signIn("uid-admin-a");
		mvc.perform(put("/api/v1/calendar/{d}/override", EKADASHI)
						.header("Authorization", "Bearer valid-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"isEkadashi\":false}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-4001"));
	}

	@Test
	@DisplayName("kitchen staff cannot override the calendar")
	void onlyAdminMayOverride() throws Exception {
		signIn("uid-staff-a");
		mvc.perform(setOverride(EKADASHI, false, "nope")).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private MockHttpServletRequestBuilder getDay(String date) {
		return get("/api/v1/calendar/{d}", date).header("Authorization", "Bearer valid-token");
	}

	private MockHttpServletRequestBuilder setOverride(String date, boolean isEkadashi, String reason) {
		return put("/api/v1/calendar/{d}/override", date)
				.header("Authorization", "Bearer valid-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"isEkadashi\":" + isEkadashi + ",\"reason\":\"" + reason + "\"}");
	}

	private int auditCount(String action) {
		Integer c = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = ?", Integer.class, action);
		return c == null ? 0 : c;
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private void insertUser(UUID tenantId, String uid, String email, String role) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE')
				""", tenantId, uid, email, role);
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
