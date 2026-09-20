package org.iskcon.kms.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.meal.MealFixture;
import org.iskcon.kms.meal.MealKindService;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * One job card per kitchen (Epic 12, T-356).
 *
 * <p>Rajeev, 2026-09-19: a meal cooked by two kitchens shows one meal card with a section per kitchen,
 * and each kitchen gets its own job card, downloaded from its own section, listing only that kitchen's
 * dishes. The fixture is the case that decision was made about: one Lunch, the main kitchen cooking
 * rice and dal, the sweets kitchen cooking halva.
 *
 * <p>What is worth proving is what would go wrong on paper. The sweets kitchen's sheet must not carry
 * the rice — a cook would make it twice, or trust that somebody else had. Each sheet must say whose it
 * is where a cook looks first. Both must carry the meal's one filing number. Each kitchen's version
 * must move only when its own sheet changes, or the main kitchen goes hunting on its v2 for a change
 * that was only ever to the halva. And a request that does not say whose card it wants, on a meal with
 * two, must be refused rather than guessed.
 *
 * <p>The meals and their sections are written by SQL rather than through the meal save. How a meal
 * comes to have two kitchens is T-354's to test; this is what the card does with them once it does.
 * The meal row is inserted here, not through {@link MealFixture#meal}, because that helper also gives
 * the meal a section of its own choosing, and this fixture has to decide every section itself.
 *
 * <p>Signed in as the Temple Admin, who may plan for every kitchen, so that the planner's own kitchen
 * check (T-357) has nothing to say about these requests and the refusals asserted here are the card's.
 */
@AutoConfigureMockMvc
class JobCardPerKitchenIT extends AbstractIntegrationTest {

	private static final LocalDate DAY = LocalDate.of(2025, 3, 17);

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private MealKindService mealKindService;

	@Autowired
	private DocumentGenerationService generationService;

	@Autowired
	private JobCardService jobCardService;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID otherTenant;
	private UUID adminUser;
	private UUID otherAdmin;

	private UUID mainKitchen;
	private UUID sweetsKitchen;
	/** A kitchen of this temple that is not cooking the Lunch. */
	private UUID deityKitchen;
	/** Another temple's kitchen. */
	private UUID foreignKitchen;
	/** Another temple's meal. */
	private UUID foreignMeal;

	private UUID lunch;
	private UUID halvaDish;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());

		tenant = temple("radha-govinda", "Sri Sri Radha Govinda Temple");
		adminUser = user(tenant, "uid-admin-k", "admin-k@example.com", "TEMPLE_ADMIN");

		mainKitchen = kitchen(tenant, "Main kitchen", true, adminUser);
		sweetsKitchen = kitchen(tenant, "Sweets kitchen", false, adminUser);
		deityKitchen = kitchen(tenant, "Deity kitchen", false, adminUser);

		// Ingredient names that share no word with any recipe name, so "the sweets card does not
		// mention the rice" is a statement about the rice and not about a word that happens to recur.
		UUID sonaMasoori = ingredient(tenant, "Sona Masoori");
		UUID pigeonPea = ingredient(tenant, "Pigeon pea");
		UUID semolina = ingredient(tenant, "Semolina");
		UUID category = admin.queryForObject(
				"INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Mains') RETURNING id",
				UUID.class, tenant);
		UUID rice = recipe(tenant, category, "Jeera Rice", sonaMasoori, "Rinse the grain.");
		UUID dal = recipe(tenant, category, "Toor Dal", pigeonPea, "Pressure cook.");
		UUID halva = recipe(tenant, category, "Rava Halva", semolina, "Roast in ghee.");

		TenantContext.set(tenant);
		try {
			mealKindService.seedForCurrentTenant();
		} finally {
			TenantContext.clear();
		}

		// The Lunch: two sections, each with its own People needed.
		lunch = meal(tenant, "Lunch");
		section(tenant, lunch, mainKitchen, 6);
		section(tenant, lunch, sweetsKitchen, 2);
		dish(tenant, lunch, rice, mainKitchen, 100);
		dish(tenant, lunch, dal, mainKitchen, 100);
		halvaDish = dish(tenant, lunch, halva, sweetsKitchen, 100);

		// Another temple, with a meal and a kitchen of its own, for the isolation cases.
		otherTenant = temple("other-temple", "Another Temple");
		otherAdmin = user(otherTenant, "uid-admin-other", "admin-other@example.com", "TEMPLE_ADMIN");
		foreignKitchen = kitchen(otherTenant, "Main kitchen", true, otherAdmin);
		UUID otherCategory = admin.queryForObject(
				"INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Mains') RETURNING id",
				UUID.class, otherTenant);
		UUID otherRecipe = recipe(otherTenant, otherCategory, "Pongal",
				ingredient(otherTenant, "Moong"), "Boil.");
		foreignMeal = meal(otherTenant, "Lunch");
		section(otherTenant, foreignMeal, foreignKitchen, 3);
		dish(otherTenant, foreignMeal, otherRecipe, foreignKitchen, 50);

		stubVerifier.accept("uid-admin-k");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM documents");
		admin.execute("DELETE FROM document_label_translations");
		MealFixture.deleteAll(admin);
		admin.execute("DELETE FROM staff_schedule_template");
		admin.execute("DELETE FROM staff_profiles");
		admin.execute("DELETE FROM kitchens");
		admin.execute("DELETE FROM recipe_translations");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- Whose dishes ------------------------------------------------------------------------------

	@Test
	@DisplayName("each kitchen's card lists only its own dishes, their recipes and their ingredients, and says whose it is")
	void eachKitchenPrintsOnlyItsOwnDishes() throws Exception {
		String sweets = print(sweetsKitchen);
		String main = print(mainKitchen);
		keep("T-356-card-sweets.html", sweets);
		keep("T-356-card-main.html", main);

		// The sweets kitchen's sheet: the halva, its recipe and what goes in it, under its own name.
		assertThat(sweets)
				.contains("<span class=\"name\">Rava Halva</span>")
				.contains("Semolina")
				.contains("Roast in ghee.")
				.contains("<div class=\"meal\">Lunch · Sweets kitchen</div>")
				.contains("Lunch · Sweets kitchen · Monday 17 March 2025")
				// Nothing of the main kitchen's — not in the food items, the appendix or the serving sheet.
				.doesNotContain("Jeera Rice")
				.doesNotContain("Toor Dal")
				.doesNotContain("Sona Masoori")
				.doesNotContain("Pigeon pea")
				.doesNotContain("Main kitchen");

		// And the other way round.
		assertThat(main)
				.contains("<span class=\"name\">Jeera Rice</span>")
				.contains("<span class=\"name\">Toor Dal</span>")
				.contains("Sona Masoori")
				.contains("Pigeon pea")
				.contains("<div class=\"meal\">Lunch · Main kitchen</div>")
				.doesNotContain("Rava Halva")
				.doesNotContain("Semolina")
				.doesNotContain("Sweets kitchen");

		// One pen box per preparation on each of the two sheets that carry them: two for the halva,
		// four for rice and dal.
		assertThat(countOf(sweets, "<td class=\"pen\"><span class=\"box\"></span></td>")).isEqualTo(2);
		assertThat(countOf(main, "<td class=\"pen\"><span class=\"box\"></span></td>")).isEqualTo(4);
	}

	@Test
	@DisplayName("both kitchens' cards carry the meal's one card number")
	void bothCarryTheSameCardNumber() throws Exception {
		String main = print(mainKitchen);
		String sweets = print(sweetsKitchen);

		String issued = admin.queryForObject("SELECT card_number FROM meals WHERE id = ?", String.class, lunch);
		assertThat(issued).isNotBlank();
		assertThat(cardNumberOn(main)).isEqualTo(issued);
		assertThat(cardNumberOn(sweets)).isEqualTo(issued);
		// One number spent for the meal, not one per kitchen.
		assertThat(admin.queryForObject("SELECT count(DISTINCT card_number) FROM meals WHERE id = ?",
				Integer.class, lunch)).isEqualTo(1);
		// The browser tab — and the file name a saved print view is offered — says whose it is too.
		assertThat(sweets).contains("<title>" + issued + " · Sweets kitchen</title>");
	}

	@Test
	@DisplayName("each kitchen's card prints that kitchen's People needed, not the meal's")
	void eachCardPrintsItsKitchensCrew() throws Exception {
		assertThat(print(mainKitchen)).contains("People needed · 6 people").doesNotContain("8 people");
		assertThat(print(sweetsKitchen)).contains("People needed · 2 people").doesNotContain("8 people");
	}

	// ---- Versions ----------------------------------------------------------------------------------

	@Test
	@DisplayName("reprinting unchanged keeps a kitchen's version; changing the halva moves only the sweets card")
	void eachKitchenVersionsOnItsOwnContent() throws Exception {
		assertThat(print(mainKitchen)).contains("v1 · printed");
		assertThat(print(mainKitchen)).contains("v1 · printed");
		assertThat(print(sweetsKitchen)).contains("v1 · printed");

		admin.update("UPDATE meal_dishes SET target_yield = 140 WHERE id = ?", halvaDish);

		assertThat(print(sweetsKitchen)).contains("v2 · printed");
		// Nothing on the main kitchen's sheet changed, so it is the same sheet.
		assertThat(print(mainKitchen)).contains("v1 · printed");

		assertThat(versionOf(mainKitchen)).isEqualTo(1);
		assertThat(versionOf(sweetsKitchen)).isEqualTo(2);
	}

	@Test
	@DisplayName("a card printed before the kitchen went on the sheet keeps its version")
	void aCardPrintedBeforeTheKitchenWasNamedKeepsItsVersion() throws Exception {
		UUID dinner = oneKitchenDinner();
		assertThat(printOmitted(dinner)).contains("v1 · printed");

		// Put the section back the way V150 left it for a card printed before T-356: version 1, and
		// the fingerprint as the old code took it, with no kitchen in it.
		String kitchenless = JobCardService.fingerprint(
				asTenant(() -> jobCardService.build(dinner, null, null, true)), null, false);
		admin.update("UPDATE meal_kitchens SET card_fingerprint = ? WHERE meal_id = ?", kitchenless, dinner);

		// Nothing about the dinner changed; the kitchen's name going on the sheet is not a new version.
		assertThat(printOmitted(dinner)).contains("v1 · printed");
		assertThat(admin.queryForObject(
				"SELECT card_fingerprint FROM meal_kitchens WHERE meal_id = ?", String.class, dinner))
				.isNotEqualTo(kitchenless);
	}

	// ---- Refusals ----------------------------------------------------------------------------------

	@Test
	@DisplayName("a two-kitchen meal with no kitchen named is refused, and spends no card number")
	void twoKitchensAndNoneNamedIsRefused() throws Exception {
		mvc.perform(get("/api/v1/job-cards/print")
						.param("mealId", lunch.toString())
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400186"));
		mvc.perform(post("/api/v1/job-cards")
						.param("mealId", lunch.toString())
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400186"));

		assertThat(admin.queryForObject("SELECT card_number FROM meals WHERE id = ?", String.class, lunch))
				.isNull();
		assertThat(admin.queryForObject("SELECT count(*) FROM documents", Integer.class)).isZero();
	}

	@Test
	@DisplayName("a kitchen of this temple that is not cooking the meal is refused")
	void aKitchenNotOnTheMealIsRefused() throws Exception {
		printAs(lunch, deityKitchen)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-400187"));
		mvc.perform(post("/api/v1/job-cards")
						.param("mealId", lunch.toString())
						.param("kitchenId", deityKitchen.toString())
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-400187"));

		assertThat(admin.queryForObject("SELECT card_number FROM meals WHERE id = ?", String.class, lunch))
				.isNull();
	}

	@Test
	@DisplayName("another temple's kitchen and another temple's meal are out of reach")
	void anotherTemplesMealAndKitchenAreOutOfReach() throws Exception {
		// Row-level security hides the other temple's kitchen, so to this temple it is simply not a
		// kitchen on this meal — the same refusal as any other, and no hint that it exists.
		printAs(lunch, foreignKitchen)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-400187"));

		// And the other temple's meal is not found at all, with or without its own kitchen named.
		printAs(foreignMeal, foreignKitchen).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-400030"));
		mvc.perform(get("/api/v1/job-cards/print")
						.param("mealId", foreignMeal.toString())
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-400030"));

		// Nothing was issued or written in the other temple.
		assertThat(admin.queryForObject("SELECT card_number FROM meals WHERE id = ?", String.class, foreignMeal))
				.isNull();
		assertThat(admin.queryForObject(
				"SELECT card_version FROM meal_kitchens WHERE meal_id = ?", Integer.class, foreignMeal))
				.isZero();
	}

	@Test
	@DisplayName("each kitchen's card lists that kitchen's rostered staff, and nobody else's")
	void eachCardListsOnlyItsKitchensRoster() throws Exception {
		// Three cooks on the Monday this Lunch is cooked, due at noon.
		roster("Govinda Das", mainKitchen, "+919876500111", LocalTime.of(6, 0), LocalTime.of(14, 0));
		roster("Radha Devi", sweetsKitchen, "+919876500112", LocalTime.of(6, 0), LocalTime.of(14, 0));
		// In the main kitchen, but gone two hours before the food is due. The planner does not count
		// them in "of 6" and the card must not print them either.
		roster("Nitai Das", mainKitchen, "+919876500113", LocalTime.of(5, 0), LocalTime.of(10, 0));

		String main = print(mainKitchen);
		String sweets = print(sweetsKitchen);

		assertThat(main)
				.contains("<h3>Staff · 1</h3>")
				.contains("Govinda Das")
				.contains("+919876500111")
				.doesNotContain("Radha Devi")
				.doesNotContain("Nitai Das");

		assertThat(sweets)
				.contains("<h3>Staff · 1</h3>")
				.contains("Radha Devi")
				.contains("+919876500112")
				.doesNotContain("Govinda Das")
				.doesNotContain("Nitai Das");
	}

	/** One cook in a kitchen, working the Monday this meal is cooked, between those hours. */
	private void roster(String name, UUID kitchenId, String phone, LocalTime from, LocalTime to) {
		UUID profile = admin.queryForObject("""
				INSERT INTO staff_profiles (tenant_id, full_name, phone, job_title, employment_type,
						employment_status, date_of_joining, kitchen_id)
				VALUES (?, ?, ?, 'COOK', 'FULL_TIME', 'ACTIVE', DATE '2024-01-01', ?) RETURNING id
				""", UUID.class, tenant, name, phone, kitchenId);
		// DAY, 2025-03-17, is a Monday: ISO day 1.
		admin.update("""
				INSERT INTO staff_schedule_template (tenant_id, staff_profile_id, day_of_week, working,
						start_time, end_time)
				VALUES (?, ?, 1, true, ?, ?)
				""", tenant, profile, from, to);
	}

	// ---- One kitchen, as before --------------------------------------------------------------------

	@Test
	@DisplayName("a one-kitchen meal still prints with no kitchen named, and the card names its kitchen")
	void oneKitchenAndNoneNamedStillPrints() throws Exception {
		UUID dinner = oneKitchenDinner();

		String html = printOmitted(dinner);
		assertThat(html)
				.contains("<div class=\"meal\">Dinner · Main kitchen</div>")
				.contains("<span class=\"name\">Jeera Rice</span>")
				.contains("v1 · printed");
	}

	// ---- The queued PDF ----------------------------------------------------------------------------

	@Test
	@DisplayName("a queued card carries its kitchen to the worker, named or resolved")
	void theQueuedCardCarriesItsKitchen() throws Exception {
		UUID sweetsDoc = request(lunch, sweetsKitchen);
		asTenant(() -> {
			generationService.generate(sweetsDoc);
			return null;
		});
		assertThat(admin.queryForObject("SELECT kitchen_id FROM documents WHERE id = ?", UUID.class, sweetsDoc))
				.isEqualTo(sweetsKitchen);
		assertThat(admin.queryForObject("SELECT status FROM documents WHERE id = ?", String.class, sweetsDoc))
				.isEqualTo("READY");
		// The worker rendered the sweets kitchen's card: its section is the one that was versioned.
		assertThat(versionOf(sweetsKitchen)).isEqualTo(1);
		assertThat(versionOf(mainKitchen)).isZero();

		// Not named on a one-kitchen meal: the kitchen that was resolved is stored, so the worker
		// renders exactly what was checked.
		UUID dinner = oneKitchenDinner();
		UUID dinnerDoc = request(dinner, null);
		assertThat(admin.queryForObject("SELECT kitchen_id FROM documents WHERE id = ?", UUID.class, dinnerDoc))
				.isEqualTo(mainKitchen);
	}

	// ---------------------------------------------------------------------

	/** A Dinner cooked by the main kitchen alone, with one dish. */
	private UUID oneKitchenDinner() {
		UUID dinner = meal(tenant, "Dinner");
		section(tenant, dinner, mainKitchen, null);
		UUID rice = admin.queryForObject(
				"SELECT id FROM recipes WHERE tenant_id = ? AND name = 'Jeera Rice'", UUID.class, tenant);
		dish(tenant, dinner, rice, mainKitchen, 80);
		return dinner;
	}

	private String print(UUID kitchenId) throws Exception {
		return printAs(lunch, kitchenId).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private String printOmitted(UUID mealId) throws Exception {
		return mvc.perform(get("/api/v1/job-cards/print")
						.param("mealId", mealId.toString())
						.header("Authorization", "Bearer valid-token"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private ResultActions printAs(UUID mealId, UUID kitchenId) throws Exception {
		return mvc.perform(get("/api/v1/job-cards/print")
				.param("mealId", mealId.toString())
				.param("kitchenId", kitchenId.toString())
				.header("Authorization", "Bearer valid-token"));
	}

	private UUID request(UUID mealId, UUID kitchenId) throws Exception {
		var call = post("/api/v1/job-cards")
				.param("mealId", mealId.toString())
				.header("Authorization", "Bearer valid-token");
		if (kitchenId != null) {
			call = call.param("kitchenId", kitchenId.toString());
		}
		String body = mvc.perform(call).andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(body.replaceAll(".*\"documentId\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
	}

	private int versionOf(UUID kitchenId) {
		return admin.queryForObject(
				"SELECT card_version FROM meal_kitchens WHERE meal_id = ? AND kitchen_id = ?",
				Integer.class, lunch, kitchenId);
	}

	private static String cardNumberOn(String html) {
		Matcher m = Pattern.compile("<span class=\"card-no\">([^<]+)</span>").matcher(html);
		assertThat(m.find()).as("a card number in the running footer").isTrue();
		return m.group(1);
	}

	/**
	 * Keeps a rendered card for a person to open, where {@code KMS_SAVE_JOB_CARDS} names a directory.
	 * Does nothing otherwise: the assertions are the test, and this is only so the sheets can be
	 * looked at.
	 */
	private static void keep(String name, String html) throws IOException {
		String dir = System.getenv("KMS_SAVE_JOB_CARDS");
		if (dir != null && !dir.isBlank()) {
			Files.writeString(Path.of(dir, name), html, StandardCharsets.UTF_8);
		}
	}

	private <T> T asTenant(java.util.function.Supplier<T> work) {
		TenantContext.set(tenant);
		try {
			return work.get();
		} finally {
			TenantContext.clear();
		}
	}

	private UUID temple(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, name);
	}

	private UUID user(UUID tenantId, String uid, String email, String role) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, '+919876500081', ?, 'ACTIVE') RETURNING id
				""", UUID.class, tenantId, uid, email, role);
	}

	private UUID kitchen(UUID tenantId, String name, boolean main, UUID createdBy) {
		return admin.queryForObject("""
				INSERT INTO kitchens (tenant_id, name, is_main, uses_meal_planner, status, created_by)
				VALUES (?, ?, ?, true, 'ACTIVE', ?) RETURNING id
				""", UUID.class, tenantId, name, main, createdBy);
	}

	private UUID ingredient(UUID tenantId, String name) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', 'KG') RETURNING id
				""", UUID.class, tenantId, name);
	}

	private UUID recipe(UUID tenantId, UUID category, String name, UUID ingredient, String method) {
		UUID recipe = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit, method)
				VALUES (?, ?, ?, 100, 'KG', ?) RETURNING id
				""", UUID.class, tenantId, name, category, method);
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit, line_order)
				VALUES (?, ?, ?, 5, 'KG', 0)
				""", tenantId, recipe, ingredient);
		return recipe;
	}

	private UUID meal(UUID tenantId, String kind) {
		UUID day = MealFixture.day(admin, tenantId, DAY);
		UUID kindId = MealFixture.ensureKind(admin, tenantId, kind);
		UUID meal = admin.queryForObject("""
				INSERT INTO meals (tenant_id, meal_plan_day_id, meal_kind_id, ready_by)
				VALUES (?, ?, ?, ?) RETURNING id
				""", UUID.class, tenantId, day, kindId, LocalTime.NOON);
		MealFixture.headCount(admin, meal, 100, 0, 0);
		return meal;
	}

	private void section(UUID tenantId, UUID mealId, UUID kitchenId, Integer crew) {
		admin.update("""
				INSERT INTO meal_kitchens (tenant_id, meal_id, kitchen_id, crew_required) VALUES (?, ?, ?, ?)
				""", tenantId, mealId, kitchenId, crew);
	}

	private UUID dish(UUID tenantId, UUID mealId, UUID recipeId, UUID kitchenId, int yield) {
		return admin.queryForObject("""
				INSERT INTO meal_dishes (tenant_id, meal_id, recipe_id, target_yield, status, created_by, kitchen_id)
				VALUES (?, ?, ?, ?, 'PLANNED', ?, ?) RETURNING id
				""", UUID.class, tenantId, mealId, recipeId, BigDecimal.valueOf(yield),
				tenantId.equals(tenant) ? adminUser : otherAdmin, kitchenId);
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
}
