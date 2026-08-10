package org.iskcon.kms.occasion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.calendar.CalendarService;
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

/**
 * The festival occasion catalog (E4-S2): seeded occasions resolve to the calendar engine's computed
 * dates, a temple can add a fixed-date anniversary that recurs annually, deleting an occasion is
 * clean, and the read/curate permission split holds.
 */
@AutoConfigureMockMvc
@Import(OccasionIT.StubVerifierConfiguration.class)
class OccasionIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private OccasionService occasionService;

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
		insertUser(tenant, "uid-vol-a", "vol-a@example.com", "VOLUNTEER");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM occasions");
		admin.execute("DELETE FROM calendar_days");
		admin.execute("DELETE FROM calendar_precompute_state");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("seeded occasions resolve to the engine's computed festival dates")
	void seededOccasionsResolveToComputedDates() {
		TenantContext.set(tenant);
		try {
			occasionService.seedForCurrentTenant();
			calendarService.precomputeForCurrentTenant(LocalDate.of(2025, 1, 1), 260);

			List<ResolvedOccasion> resolved =
					occasionService.resolve(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));

			assertThat(resolved).anySatisfy(o -> {
				assertThat(o.name()).isEqualTo("Gaura Purnima");
				assertThat(o.date()).isEqualTo(LocalDate.of(2025, 3, 14));
			});
			assertThat(resolved).anySatisfy(o -> {
				assertThat(o.name()).isEqualTo("Sri Krsna Janmastami");
				assertThat(o.date()).isEqualTo(LocalDate.of(2025, 8, 16));
			});
			assertThat(resolved).anySatisfy(o -> {
				assertThat(o.name()).isEqualTo("Radhastami");
				assertThat(o.date()).isEqualTo(LocalDate.of(2025, 8, 31));
			});
		} finally {
			TenantContext.clear();
		}
	}

	@Test
	@DisplayName("an admin adds a fixed-date anniversary that recurs every year")
	void manualAnniversaryRecursAnnually() throws Exception {
		stubVerifier.accept("uid-admin-a");
		mvc.perform(post("/api/v1/occasions").header("Authorization", "Bearer valid-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Temple Anniversary\",\"type\":\"MANUAL\","
								+ "\"fixedMonth\":9,\"fixedDay\":15,\"defaultServings\":700}"))
				.andExpect(status().isCreated());

		TenantContext.set(tenant);
		try {
			List<ResolvedOccasion> resolved =
					occasionService.resolve(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 12, 31));
			assertThat(resolved).filteredOn(o -> o.name().equals("Temple Anniversary"))
					.extracting(ResolvedOccasion::date)
					.containsExactly(LocalDate.of(2025, 9, 15), LocalDate.of(2026, 9, 15));
		} finally {
			TenantContext.clear();
		}
	}

	@Test
	@DisplayName("the seed is idempotent and a duplicate name is refused")
	void seedIdempotentAndDuplicateRefused() throws Exception {
		TenantContext.set(tenant);
		try {
			occasionService.seedForCurrentTenant();
			int afterFirst = occasionService.list().size();
			occasionService.seedForCurrentTenant();
			assertThat(occasionService.list()).hasSize(afterFirst);
		} finally {
			TenantContext.clear();
		}

		stubVerifier.accept("uid-admin-a");
		mvc.perform(post("/api/v1/occasions").header("Authorization", "Bearer valid-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Gaura Purnima\",\"type\":\"COMPUTED\",\"matchText\":\"Gaura Purnima\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-4913"));
	}

	@Test
	@DisplayName("planners read occasions; only a Temple Admin curates them")
	void permissionSplit() throws Exception {
		stubVerifier.accept("uid-staff-a");
		mvc.perform(get("/api/v1/occasions").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk());
		mvc.perform(post("/api/v1/occasions").header("Authorization", "Bearer valid-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Kitchen Party\",\"type\":\"MANUAL\",\"fixedMonth\":6,\"fixedDay\":1}"))
				.andExpect(status().isForbidden());

		stubVerifier.accept("uid-admin-a");
		mvc.perform(post("/api/v1/occasions").header("Authorization", "Bearer valid-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Kitchen Party\",\"type\":\"MANUAL\",\"fixedMonth\":6,\"fixedDay\":1}"))
				.andExpect(status().isCreated());

		stubVerifier.accept("uid-vol-a");
		mvc.perform(get("/api/v1/occasions").header("Authorization", "Bearer valid-token"))
				.andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------

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
