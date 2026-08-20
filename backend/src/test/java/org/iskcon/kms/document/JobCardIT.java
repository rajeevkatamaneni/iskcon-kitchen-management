package org.iskcon.kms.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.iskcon.kms.meal.MealKindService;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The job card (B5): the sheet that goes to the kitchen and comes back signed.
 *
 * <p>What is worth proving here is what the brief argued about. The card number has to be stable
 * once issued and never repeated within a temple, because its whole purpose is tracing a signed
 * sheet in a folder back to its record months later. The card has to print in the temple's own
 * language and in English, chosen at print time. And it has to be printable by a cook — a worksheet
 * behind an administrator's permission would mean asking somebody else for your own job sheet.
 *
 * <p>A mocked {@link Scheduler} keeps the request→enqueue path hermetic; the worker step is driven
 * synchronously through {@link DocumentGenerationService}, as the other document tests do.
 */
@AutoConfigureMockMvc
@Import(JobCardIT.StubVerifierConfiguration.class)
class JobCardIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private DocumentGenerationService generationService;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private MealKindService mealKindService;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID rice;
	private UUID khichdi;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Sri Sri Radha Govinda Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		insertUser("uid-staff-a", "staff-a@example.com", "KITCHEN_STAFF");
		insertUser("uid-vol-a", "vol-a@example.com", "VOLUNTEER");

		rice = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Rice', 'Grains', 'KG') RETURNING id
				""", UUID.class, tenant);
		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Rice') RETURNING id
				""", UUID.class, tenant);
		khichdi = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit, method)
				VALUES (?, 'Khichdi', ?, 100, 'SERVINGS', 'Wash the rice.
				Temper the spices.') RETURNING id
				""", UUID.class, tenant, category);
		// The same ingredient twice, as a real recipe does — once for the tempering, once for the
		// finish. The card must fold them into one line a cook can weigh out in one go.
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, 3, 'KG', 0), (?, ?, ?, 2, 'KG', 1)
				""", tenant, khichdi, rice, tenant, khichdi, rice);

		admin.update("""
				INSERT INTO equipment_items (tenant_id, name, category, condition)
				VALUES (?, 'Wet grinder', 'MACHINE', 'NEEDS_REPAIR'), (?, 'Steam cauldron', 'MACHINE', 'GOOD')
				""", tenant, tenant);

		TenantContext.set(tenant);
		try {
			mealKindService.seedForCurrentTenant();
		} finally {
			TenantContext.clear();
		}
		signIn("uid-staff-a");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM documents");
		admin.execute("DELETE FROM document_label_translations");
		admin.execute("DELETE FROM meal_services");
		admin.execute("DELETE FROM meal_card_sequence");
		admin.execute("DELETE FROM meal_plans");
		admin.execute("DELETE FROM equipment_items");
		admin.execute("DELETE FROM calendar_days");
		admin.execute("DELETE FROM meal_kinds");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("the printed card carries the meal, its scaled quantities, the equipment and the sign-off boxes")
	void theCardCarriesTheMeal() throws Exception {
		plan("Lunch", 200, 200, 0, 0);

		String html = print(null);

		assertThat(html)
				.contains("Sri Sri Radha Govinda Temple")
				.contains("Job card")
				.contains("Lunch")
				.contains("Khichdi")
				// 3 KG + 2 KG per 100 servings, doubled for 200 — folded into one line, not two.
				.contains("10 KG")
				.doesNotContain("6 KG")
				.contains("200 adults")
				.contains("Wash the rice.")
				.contains("Wet grinder (needs repair)")
				.contains("Cooked by")
				.contains("Checked by")
				.contains("Served by");
	}

	@Test
	@DisplayName("a card number is issued once, kept across reprints, and never repeats within a temple")
	void cardNumbersAreStableAndMonotonic() throws Exception {
		plan("Lunch", 100, 100, 0, 0);
		plan("Breakfast", 60, 60, 0, 0);

		String lunch = cardNumber("Lunch");
		assertThat(lunch).isEqualTo("LC-2025-0001");

		// A reprint of the same meal is the same sheet.
		assertThat(cardNumber("Lunch")).isEqualTo(lunch);
		assertThat(print(null)).contains(lunch);

		// A different meal takes the next number in the temple's own sequence, with its own prefix.
		assertThat(cardNumber("Breakfast")).isEqualTo("BC-2025-0002");
	}

	@Test
	@DisplayName("the card prints in the temple's own language, and in English when asked")
	void printsInBothLanguages() throws Exception {
		plan("Lunch", 100, 100, 0, 0);

		// The temple's locale is en-IN, so the default is English and nothing goes near the translator.
		assertThat(print(null)).contains("Job card").doesNotContain("[kn]");

		// Asked for Kannada, every word is translated — labels, dish name, ingredients — and the
		// numbers, the times and the card number are left exactly as they are.
		String kannada = print("kn");
		assertThat(kannada)
				.contains("[kn] Job card")
				.contains("[kn] Khichdi")
				.contains("[kn] Rice")
				.contains("5 KG")
				.contains("LC-2025-0001");
	}

	@Test
	@DisplayName("a cook can print their own job sheet; a volunteer cannot")
	void printableByTheKitchenAndNotByAVolunteer() throws Exception {
		plan("Lunch", 100, 100, 0, 0);

		mvc.perform(get("/api/v1/job-cards/print")
						.param("date", "2025-03-17").param("mealKind", "Lunch")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("text/html"));

		signIn("uid-vol-a");
		mvc.perform(get("/api/v1/job-cards/print")
						.param("date", "2025-03-17").param("mealKind", "Lunch")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("a requested PDF is versioned, generated and downloadable")
	void thePdfIsGeneratedAndDownloadable() throws Exception {
		plan("Lunch", 100, 100, 0, 0);

		String body = mvc.perform(post("/api/v1/job-cards")
						.param("date", "2025-03-17").param("mealKind", "Lunch")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.cardNumber").value("LC-2025-0001"))
				.andReturn().getResponse().getContentAsString();
		UUID documentId = UUID.fromString(
				body.replaceAll(".*\"documentId\"\\s*:\\s*\"([^\"]+)\".*", "$1"));

		TenantContext.set(tenant);
		try {
			generationService.generate(documentId);
		} finally {
			TenantContext.clear();
		}

		mvc.perform(get("/api/v1/job-cards/documents/{id}", documentId)
						.header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.version").value(1));

		mvc.perform(get("/api/v1/job-cards/documents/{id}/download", documentId)
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andExpect(content().contentType("application/pdf"));

		// A reprint after a dish was swapped is a different sheet, so it is a new version — but the
		// same card number, because it is still the same meal.
		mvc.perform(post("/api/v1/job-cards")
						.param("date", "2025-03-17").param("mealKind", "Lunch")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.cardNumber").value("LC-2025-0001"));

		mvc.perform(get("/api/v1/job-cards/documents")
						.param("date", "2025-03-17").param("mealKind", "Lunch")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].version").value(2));
	}

	@Test
	@DisplayName("a meal nobody planned has no card, and no number is spent on it")
	void nothingPlannedMeansNoCard() throws Exception {
		mvc.perform(get("/api/v1/job-cards/print")
						.param("date", "2025-03-17").param("mealKind", "Dinner")
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isNotFound());

		assertThat(admin.queryForObject("SELECT count(*) FROM meal_card_sequence", Integer.class))
				.isZero();
	}

	@Test
	@DisplayName("a fasting day says so on the card, in the words a cook needs")
	void aFastingDayIsOnTheCard() throws Exception {
		plan("Lunch", 100, 100, 0, 0);
		admin.update("""
				INSERT INTO calendar_days (tenant_id, cal_date, tithi, paksa, masa, is_ekadashi,
						ekadashi_name, fast_type)
				VALUES (?, DATE '2025-03-17', 10, 1, 11, true, 'Papamocani Ekadasi', 'Ekadashi')
				""", tenant);

		// Not a badge, and not a colour: a cook reading this at speed in a hot room needs the sentence.
		assertThat(print(null))
				.contains("Papamocani Ekadasi")
				.contains("No grains, dal or beans");
	}

	// ---------------------------------------------------------------------

	private void plan(String kind, int servings, int adults, int children, int seniors) {
		admin.update("""
				INSERT INTO meal_plans (tenant_id, plan_date, meal_kind, ready_by, recipe_id,
						target_servings, day_type, status, adults, children, seniors, created_by)
				VALUES (?, DATE '2025-03-17', ?, TIME '12:00', ?, ?, 'REGULAR', 'PLANNED', ?, ?, ?,
						(SELECT id FROM users WHERE firebase_uid = 'uid-staff-a'))
				""", tenant, kind, khichdi, BigDecimal.valueOf(servings), adults, children, seniors);
	}

	private String print(String language) throws Exception {
		var request = get("/api/v1/job-cards/print")
				.param("date", "2025-03-17").param("mealKind", "Lunch")
				.header("Authorization", "Bearer valid-token");
		if (language != null) {
			request = request.param("language", language);
		}
		return mvc.perform(request).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	private String cardNumber(String mealKind) throws Exception {
		String body = mvc.perform(post("/api/v1/job-cards")
						.param("date", "2025-03-17").param("mealKind", mealKind)
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();
		return body.replaceAll(".*\"cardNumber\"\\s*:\\s*\"([^\"]+)\".*", "$1");
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
