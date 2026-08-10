package org.iskcon.kms.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The calendar precompute/store/read pipeline (E4-S1): astronomy is computed per tenant location and
 * persisted, the precompute is idempotent, two cities genuinely diverge, and reads are permissioned.
 * Date-level correctness against the published ISKCON calendar is owned by the engine's
 * {@code CalendarReferenceTest}; this exercises the DB pipeline around it.
 */
@AutoConfigureMockMvc
@Import(CalendarServiceIT.StubVerifierConfiguration.class)
class CalendarServiceIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private CalendarService calendarService;

	private JdbcTemplate admin;
	private UUID bangalore;
	private UUID newYork;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		bangalore = insertTenant("radha-govinda", "Bengaluru Temple", 12.9716, 77.5946, "Asia/Kolkata");
		newYork = insertTenant("radha-newyork", "New York Temple", 40.7128, -74.0060, "America/New_York");
		insertUser(bangalore, "uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		insertUser(bangalore, "uid-vol-a", "vol-a@example.com", "VOLUNTEER");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM calendar_precompute_state");
		admin.execute("DELETE FROM calendar_days");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	private void precompute(UUID tenant, LocalDate start, int days) {
		TenantContext.set(tenant);
		try {
			calendarService.precomputeForCurrentTenant(start, days);
		} finally {
			TenantContext.clear();
		}
	}

	@Test
	@DisplayName("precompute derives and stores the calendar, Ekadashi and festivals included")
	void precomputeStoresDerivedDays() {
		precompute(bangalore, LocalDate.of(2025, 1, 1), 100);

		assertThat(dayCount(bangalore)).isEqualTo(100);
		// 10 Jan 2025 is Putrada Ekadashi in Bengaluru (see CALENDAR-CORRECTNESS.md).
		assertThat(isEkadashi(bangalore, LocalDate.of(2025, 1, 10))).isTrue();
		// Gaura Purnima falls on 14 Mar 2025 — within the window — and is a named festival.
		assertThat(festivalsText(bangalore, LocalDate.of(2025, 3, 14))).contains("Gaura Purnima");
		// The watermark for the ops page is recorded.
		assertThat(precomputeRuns(bangalore)).isEqualTo(1);
	}

	@Test
	@DisplayName("re-running precompute updates rather than duplicating")
	void precomputeIsIdempotent() {
		precompute(bangalore, LocalDate.of(2025, 1, 1), 60);
		precompute(bangalore, LocalDate.of(2025, 1, 1), 60);
		assertThat(dayCount(bangalore)).isEqualTo(60);
		assertThat(precomputeRuns(bangalore)).isEqualTo(1); // one state row, refreshed
	}

	@Test
	@DisplayName("two cities produce a different calendar — different sunrise and different Ekadashi dates")
	void twoCitiesDiverge() {
		precompute(bangalore, LocalDate.of(2025, 1, 1), 180);
		precompute(newYork, LocalDate.of(2025, 1, 1), 180);

		// Location-dependent astronomy: sunrise differs for the same date.
		LocalTime blrSunrise = sunrise(bangalore, LocalDate.of(2025, 1, 10));
		LocalTime nycSunrise = sunrise(newYork, LocalDate.of(2025, 1, 10));
		assertThat(blrSunrise).isNotEqualTo(nycSunrise);

		// And Ekadashi fasting dates diverge across ~half the globe.
		assertThat(ekadashiDates(bangalore)).isNotEqualTo(ekadashiDates(newYork));
	}

	@Test
	@DisplayName("the calendar is readable by planners and refused to volunteers")
	void readApiIsPermissioned() throws Exception {
		precompute(bangalore, LocalDate.of(2025, 1, 1), 40);

		stubVerifier.accept("uid-staff-a");
		mvc.perform(get("/api/v1/calendar")
						.param("from", "2025-01-01").param("to", "2025-01-31")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(31));

		stubVerifier.accept("uid-vol-a");
		mvc.perform(get("/api/v1/calendar")
						.param("from", "2025-01-01").param("to", "2025-01-31")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

	private int dayCount(UUID tenant) {
		return admin.queryForObject("SELECT count(*) FROM calendar_days WHERE tenant_id = ?", Integer.class, tenant);
	}

	private int precomputeRuns(UUID tenant) {
		return admin.queryForObject(
				"SELECT count(*) FROM calendar_precompute_state WHERE tenant_id = ?", Integer.class, tenant);
	}

	private boolean isEkadashi(UUID tenant, LocalDate date) {
		return admin.queryForObject(
				"SELECT is_ekadashi FROM calendar_days WHERE tenant_id = ? AND cal_date = ?",
				Boolean.class, tenant, date);
	}

	private String festivalsText(UUID tenant, LocalDate date) {
		return admin.queryForObject(
				"SELECT festivals::text FROM calendar_days WHERE tenant_id = ? AND cal_date = ?",
				String.class, tenant, date);
	}

	private LocalTime sunrise(UUID tenant, LocalDate date) {
		return admin.queryForObject(
				"SELECT sunrise FROM calendar_days WHERE tenant_id = ? AND cal_date = ?",
				LocalTime.class, tenant, date);
	}

	private List<LocalDate> ekadashiDates(UUID tenant) {
		return admin.queryForList(
				"SELECT cal_date FROM calendar_days WHERE tenant_id = ? AND is_ekadashi ORDER BY cal_date",
				LocalDate.class, tenant);
	}

	private UUID insertTenant(String slug, String name, double lat, double lon, String tz) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, ?, ?, ?)
				RETURNING id
				""", UUID.class, slug, name, lat, lon, tz);
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
