package org.iskcon.kms.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.iskcon.kms.translation.RecipeTranslationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The job card (B5, rebuilt by build brief 2026-08-21 item 17): the sheet that goes to the kitchen
 * and comes back signed.
 *
 * <p>What is worth proving here is what the brief argued about. The worksheet has to carry a ruled
 * box for what was cooked and what was served, because the gap between those and the planned figure
 * is the reason the sheet comes back to the office at all. The card number has to stay a filing
 * reference rather than a heading. The worksheet has to stay English while the recipes follow the
 * printer's choice, and that choice has to be offered only in languages a translation actually
 * exists in. And the card has to be printable by a cook — a worksheet behind an administrator's
 * permission would mean asking somebody else for your own job sheet.
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

	@Autowired
	private RecipeTranslationService recipeTranslationService;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID rice;
	private UUID khichdi;
	private UUID payasam;

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
		insertUser("uid-admin-a", "admin-a@example.com", "TEMPLE_ADMIN");

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

		// A second preparation, so that a meal can be part translated — which is the case the
		// appendix's rule exists for.
		payasam = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit, method)
				VALUES (?, 'Payasam', ?, 100, 'SERVINGS', 'Boil the milk.') RETURNING id
				""", UUID.class, tenant, category);
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, 1, 'KG', 0)
				""", tenant, payasam, rice);

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
		admin.execute("DELETE FROM staff_schedule_template");
		admin.execute("DELETE FROM staff_profiles");
		admin.execute("DELETE FROM equipment_items");
		admin.execute("DELETE FROM calendar_days");
		admin.execute("DELETE FROM meal_kinds");
		admin.execute("DELETE FROM recipe_translations");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("the printed card carries the meal, its scaled quantities, the equipment and the recipes")
	void theCardCarriesTheMeal() throws Exception {
		plan("Lunch", 200, 200, 0, 0);

		String html = print(null);

		assertThat(html)
				.contains("Sri Sri Radha Govinda Temple")
				.contains("Lunch")
				.contains("Khichdi")
				// 3 KG + 2 KG per 100 servings, doubled for 200 — folded into one line, not two.
				.contains("10 KG")
				.doesNotContain("6 KG")
				.contains("200 adults")
				.contains("Wash the rice.")
				.contains("Wet grinder (needs repair)");
	}

	@Test
	@DisplayName("the header says which meal this is; the card number is a reference underneath it")
	void theHeaderLeadsWithTheMealAndNotTheCardNumber() throws Exception {
		plan("Dinner", 133, 133, 0, 0);

		String html = print(null, "Dinner");

		// What a cook picking the sheet up needs first is which meal and which day. The number is
		// how the office finds this sheet again in six months, and it is set as the reference it is.
		assertThat(html).contains("<div class=\"meal\">Dinner &middot; Monday 17 March 2025</div>");
		assertThat(html).contains("<div class=\"card-no\">DC-2025-0001</div>");
		assertThat(html.indexOf("class=\"meal\"")).isLessThan(html.indexOf("class=\"card-no\""));

		// The emblem is inlined, because the renderer has no network and a linked image would print
		// as a broken box.
		assertThat(html).contains("<svg").contains("ISKCON lotus emblem");
	}

	@Test
	@DisplayName("the servings table prints what was planned and leaves cooked and served for a pen")
	void theServingsTableIsTheHeartOfTheSheet() throws Exception {
		plan("Lunch", khichdi, 133, 133, 0, 0);
		plan("Lunch", payasam, 133, 133, 0, 0);

		String html = print(null);

		assertThat(html)
				.contains("<th>Preparation</th>")
				.contains("<th class=\"num\">Planned</th>")
				.contains("<th class=\"pen\">Cooked</th>")
				.contains("<th class=\"pen\">Served</th>")
				// One row per preparation, its planned figure printed and two empty ruled boxes beside
				// it. Those two are the expected-versus-actual data the temple is after.
				.contains("<span class=\"name\">Khichdi</span>")
				.contains("<span class=\"name\">Payasam</span>");
		assertThat(countOf(html, "<td class=\"pen\"><span class=\"box\"></span></td>")).isEqualTo(4);
	}

	@Test
	@DisplayName("two people sign, not three: the cooked figures were checked and the served ones recorded")
	void twoSignatureBoxes() throws Exception {
		plan("Lunch", 100, 100, 0, 0);

		String html = print(null);

		assertThat(html)
				.contains("Kitchen manager / head cook")
				.contains("The cooked figures were checked.")
				.contains("Serving staff")
				.contains("The served figures were recorded.")
				// The old three asked for a name against a moment nobody is separately responsible for.
				.doesNotContain("Cooked by")
				.doesNotContain("Checked by")
				.doesNotContain("Served by");
		assertThat(countOf(html, "class=\"sign\"")).isEqualTo(2);
	}

	@Test
	@DisplayName("who is on carries a count and a number to ring")
	void whoIsOnCarriesPhoneNumbers() throws Exception {
		plan("Lunch", 100, 100, 0, 0);
		rosterStaffOnTheDay();

		String html = print(null);

		// The one thing this sheet is asked for at 05:40 is a way to ring whoever has not arrived.
		assertThat(html)
				.contains("Who is on")
				.contains("<h3>Staff &middot; 1</h3>")
				.contains("Gopal Das")
				.contains("+919876500081")
				.contains("<h3>Volunteers &middot; 0</h3>");
	}

	@Test
	@DisplayName("the planned crew prints above the names, and leaves no gap when nobody set one")
	void thePlannedCrewPrintsAboveTheNames() throws Exception {
		plan("Lunch", 100, 100, 0, 0);

		// A meal is planned weeks before anybody is rostered, so having no figure is ordinary. The
		// card is not the place to print a blank where a decision has not been taken.
		assertThat(print(null)).doesNotContain("Planned crew");

		admin.update("UPDATE meal_plans SET crew_required = 8 WHERE plan_date = DATE '2025-03-17'");
		String html = print(null);
		assertThat(html).contains("Planned crew · 8 people");
		assertThat(html.indexOf("Planned crew")).isLessThan(html.indexOf("<h3>Staff"));
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
	@DisplayName("the worksheet stays English while the recipes print in the language that was asked for")
	void theWorksheetIsEnglishAndTheRecipesAreNot() throws Exception {
		plan("Lunch", 100, 100, 0, 0);
		translateRecipe(khichdi, "kn");

		String kannada = print("kn");

		// Two halves, two readers (Q3). The office reads the worksheet and files it, so it is English
		// — the app's own UI is English-only in Phase 1. The cooks read the recipes.
		assertThat(kannada)
				.contains("<th>Preparation</th>")
				.contains("<span class=\"name\">Khichdi</span>")
				.contains("Kitchen manager / head cook")
				.contains("[kn] Khichdi")
				.contains("[kn] Rice")
				.contains("[kn] Ingredient")
				// Numbers, times and the card number are never translated.
				.contains("5 KG")
				.contains("LC-2025-0001");

		// English asks nothing of the translator at all.
		assertThat(print("en")).contains("Khichdi").doesNotContain("[kn]");
	}

	@Test
	@DisplayName("only languages a translation exists in are offered, and English always is")
	void onlyRealTranslationsAreOffered() throws Exception {
		plan("Lunch", 100, 100, 0, 0);

		// Nothing translated yet: English is the only honest answer, because it is the source text.
		languages()
				.andExpect(jsonPath("$.languages.length()").value(1))
				.andExpect(jsonPath("$.languages[0]").value("en"))
				.andExpect(jsonPath("$.defaultLanguage").value("en"));

		translateRecipe(khichdi, "kn");

		// Kannada is now offered because it exists — offering it before would have printed an English
		// appendix under a Kannada heading.
		languages()
				.andExpect(jsonPath("$.languages.length()").value(2))
				.andExpect(jsonPath("$.languages[1]").value("kn"));

		// An edit bumps the recipe's version, and a translation of the old version describes a recipe
		// that no longer exists. It comes off the list until somebody translates it again.
		admin.update("UPDATE recipes SET version = version + 1 WHERE id = ?", khichdi);
		languages()
				.andExpect(jsonPath("$.languages.length()").value(1))
				.andExpect(jsonPath("$.languages[0]").value("en"));
	}

	@Test
	@DisplayName("a temple that works in Kannada gets Kannada recipes without asking, once it has them")
	void defaultsToTheTemplesOwnLanguage() throws Exception {
		plan("Lunch", 100, 100, 0, 0);

		// A cook prints the card but does not decide what language the temple works in — that is a
		// temple-settings act, and the split is the ordinary one.
		setLanguage("kn").andExpect(status().isForbidden());

		// The setting had existed since V1 and been unwritable, so every temple was quietly English.
		// The card is the first thing to read it, which is what made it worth being able to set.
		signIn("uid-admin-a");
		setLanguage("kn").andExpect(status().isNoContent());
		signIn("uid-staff-a");

		// The temple's own language is the default, not the rule (Q3) — and a default the meal cannot
		// deliver is not a default. With nothing translated, the picker opens on English.
		languages().andExpect(jsonPath("$.defaultLanguage").value("en"));

		translateRecipe(khichdi, "kn");
		languages().andExpect(jsonPath("$.defaultLanguage").value("kn"));

		// Nobody chose at the printer, and the recipes still come out in the language the kitchen
		// reads — which is the whole point of them going to the kitchen.
		assertThat(print(null)).contains("[kn] Khichdi");

		// And English is still one choice away, for the head cook who wants it.
		assertThat(print("en")).contains("Khichdi").doesNotContain("[kn]");
	}

	@Test
	@DisplayName("a preparation with no translation prints in English, under one line saying so")
	void anUntranslatedPreparationSaysSo() throws Exception {
		plan("Lunch", khichdi, 100, 100, 0, 0);
		plan("Lunch", payasam, 100, 100, 0, 0);
		translateRecipe(khichdi, "kn");

		String html = print("kn");

		// One recipe of two in Kannada beats none, and a cook is told which one they are holding.
		assertThat(html)
				.contains("[kn] Khichdi")
				.contains("Payasam")
				.contains("[kn] Not translated yet. Printed in English.")
				.contains("Boil the milk.");
		assertThat(countOf(html, "class=\"untranslated\"")).isEqualTo(1);
	}

	@Test
	@DisplayName("the recipes are optional; the worksheet prints on its own when they are not wanted")
	void theRecipesAreOptional() throws Exception {
		plan("Lunch", 100, 100, 0, 0);

		String worksheetOnly = print("none");

		// The worksheet is unchanged; the appendix simply is not there.
		assertThat(worksheetOnly)
				.contains("<th>Preparation</th>")
				.contains("<span class=\"name\">Khichdi</span>")
				.doesNotContain("class=\"appendix\"")
				.doesNotContain("Wash the rice.");

		// Asked for, they come back — and they start their own page rather than being woven through
		// the sheet that goes back to the office.
		assertThat(print("en")).contains("class=\"appendix\"").contains("Wash the rice.");
	}

	@Test
	@DisplayName("the print window previews as an A4 page rather than going edge to edge")
	void theScreenPreviewLooksLikeThePrint() throws Exception {
		plan("Lunch", 100, 100, 0, 0);

		// The PDF was always right — @page carries the margin. The window the Print button opens had
		// no page of its own, so the same file was two different sheets.
		assertThat(print(null))
				.contains("@page{size:A4;margin:16mm}")
				.contains("@media screen{")
				.contains("width:210mm;min-height:297mm;margin:8mm auto;padding:16mm");
	}

	@Test
	@DisplayName("the font stack names a family per script rather than trusting codepoint fallback")
	void theFontStackNamesEveryScriptTheImageHas() throws Exception {
		plan("Lunch", 100, 100, 0, 0);

		// Telugu and Tamil reached the page through Chromium's own fallback, which picks whatever
		// fontconfig ranks first and can change when the base image does.
		assertThat(print(null))
				.contains("'Noto Sans Devanagari'")
				.contains("'Noto Sans Kannada'")
				.contains("'Noto Sans Telugu'")
				.contains("'Noto Sans Tamil'")
				.contains("'Noto Sans Bengali'")
				.contains("'Noto Sans Malayalam'");
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
		plan(kind, khichdi, servings, adults, children, seniors);
	}

	private void plan(String kind, UUID recipe, int servings, int adults, int children, int seniors) {
		admin.update("""
				INSERT INTO meal_plans (tenant_id, plan_date, meal_kind, ready_by, recipe_id,
						target_yield, day_type, status, adults, children, seniors, created_by)
				VALUES (?, DATE '2025-03-17', ?, TIME '12:00', ?, ?, 'REGULAR', 'PLANNED', ?, ?, ?,
						(SELECT id FROM users WHERE firebase_uid = 'uid-staff-a'))
				""", tenant, kind, recipe, BigDecimal.valueOf(servings), adults, children, seniors);
	}

	/**
	 * Stores a translation the way the app does — through the recipe translation service, which is
	 * the only thing that ever writes {@code recipe_translations}. The card reads what is there and
	 * never asks for a translation of its own, so a test that inserted a row by hand would be
	 * testing a table rather than the rule.
	 */
	private void translateRecipe(UUID recipeId, String language) {
		TenantContext.set(tenant);
		try {
			recipeTranslationService.translate(recipeId, language);
		} finally {
			TenantContext.clear();
		}
	}

	private String print(String language, String mealKind) throws Exception {
		var request = get("/api/v1/job-cards/print")
				.param("date", "2025-03-17").param("mealKind", mealKind)
				.header("Authorization", "Bearer valid-token");
		if (language != null) {
			request = request.param("language", language);
		}
		return mvc.perform(request).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	private String print(String language) throws Exception {
		return print(language, "Lunch");
	}

	private org.springframework.test.web.servlet.ResultActions languages() throws Exception {
		return mvc.perform(get("/api/v1/job-cards/languages")
				.param("date", "2025-03-17").param("mealKind", "Lunch")
				.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk());
	}

	/**
	 * Rosters the one staff member on the day the card is printed for, through the template the week
	 * grid reads — the card must agree with the grid rather than hold a second opinion of the roster.
	 */
	private void rosterStaffOnTheDay() {
		UUID profile = admin.queryForObject("""
				INSERT INTO staff_profiles (tenant_id, user_id, full_name, phone, job_title,
						employment_type, employment_status, date_of_joining)
				VALUES (?, (SELECT id FROM users WHERE firebase_uid = 'uid-staff-a'), 'Gopal Das',
						'+919876500081', 'HEAD_COOK', 'FULL_TIME', 'ACTIVE', DATE '2024-01-01')
				RETURNING id
				""", UUID.class, tenant);
		// 2025-03-17 is a Monday.
		admin.update("""
				INSERT INTO staff_schedule_template (tenant_id, staff_profile_id, day_of_week, working,
						start_time, end_time)
				VALUES (?, ?, 1, true, TIME '06:00', TIME '14:00')
				""", tenant, profile);
	}

	private static int countOf(String haystack, String needle) {
		int count = 0;
		int at = haystack.indexOf(needle);
		while (at >= 0) {
			count++;
			at = haystack.indexOf(needle, at + needle.length());
		}
		return count;
	}

	private String cardNumber(String mealKind) throws Exception {
		String body = mvc.perform(post("/api/v1/job-cards")
						.param("date", "2025-03-17").param("mealKind", mealKind)
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();
		return body.replaceAll(".*\"cardNumber\"\\s*:\\s*\"([^\"]+)\".*", "$1");
	}

	private org.springframework.test.web.servlet.ResultActions setLanguage(String language) throws Exception {
		return mvc.perform(put("/api/v1/settings/language")
				.header("Authorization", "Bearer valid-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"language\":\"" + language + "\"}"));
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
