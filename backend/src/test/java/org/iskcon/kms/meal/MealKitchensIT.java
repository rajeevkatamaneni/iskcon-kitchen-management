package org.iskcon.kms.meal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A meal is saved and read by kitchen (Epic 12, T-354).
 *
 * <p>What is proved here, all through the real endpoints and the real database under row-level
 * security:
 * <ul>
 *   <li>A meal with two kitchens and a dish under each round-trips: the sections, each dish's kitchen,
 *       each kitchen's People needed, and the meal's figure as their sum.</li>
 *   <li>A save that names no kitchens goes to the saver's own planning kitchen, and to the main kitchen
 *       for a Temple Admin with no staff record.</li>
 *   <li>Every refusal: no kitchen (KMS-400180), a kitchen that does not plan meals — archived, or off
 *       the planner (KMS-400181) — and a dish under a kitchen that is not on the meal: named twice,
 *       foreign, or missing with two sections (KMS-400182). Each writes nothing.</li>
 *   <li>An edit takes a kitchen off a meal only when no dish sent is under it.</li>
 *   <li>The sections come in the viewer's order: a cook in the Sweets kitchen sees Sweets first, the
 *       Temple Admin sees the main kitchen first.</li>
 *   <li>Reusing a plan and repeating an event carry the kitchens and their figures.</li>
 *   <li>Another temple cannot see these sections, nor name this temple's kitchen.</li>
 * </ul>
 *
 * <p>The cook has a staff record in the Sweets kitchen; the Temple Admin has none. That is the case
 * {@code KitchenOrder} was written for, and it keeps this class right when the planner guard (T-357)
 * starts turning away Kitchen Staff with no kitchen.
 *
 * <p>{@code NotificationService} is mocked, and only that, so this class shares the cached context
 * of the other meal classes that mock exactly that bean.
 */
@AutoConfigureMockMvc
class MealKitchensIT extends AbstractIntegrationTest {

	private static final String DAY = "2025-03-18";
	private static final ObjectMapper JSON = new ObjectMapper();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private MealKindService mealKindService;

	@MockBean
	private NotificationService notificationService;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID lunch;
	private UUID event;
	private UUID khichdi;
	private UUID payasam;
	private UUID main;
	private UUID sweets;
	private UUID store;
	private UUID archived;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());
		tenant = temple("kitchens-temple");
		UUID templeAdmin = user(tenant, "uid-admin", "admin@example.com", "TEMPLE_ADMIN", "+919876500401");
		UUID cook = user(tenant, "uid-cook", "cook@example.com", "KITCHEN_STAFF", "+919876500402");

		main = kitchen(tenant, "Main kitchen", true, true, "ACTIVE", templeAdmin);
		sweets = kitchen(tenant, "Sweets kitchen", false, true, "ACTIVE", templeAdmin);
		store = kitchen(tenant, "Store room", false, false, "ACTIVE", templeAdmin);
		archived = kitchen(tenant, "Old kitchen", false, true, "ARCHIVED", templeAdmin);
		admin.update("""
				INSERT INTO staff_profiles
					(tenant_id, user_id, full_name, job_title, employment_type, date_of_joining, kitchen_id)
				VALUES (?, ?, 'Test Cook', 'COOK', 'FULL_TIME', DATE '2026-01-01', ?)
				""", tenant, cook, sweets);

		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Mains') RETURNING id
				""", UUID.class, tenant);
		khichdi = recipe(tenant, "Khichdi", category);
		payasam = recipe(tenant, "Payasam", category);

		seedKinds(tenant);
		lunch = MealFixture.kindId(admin, tenant, "Lunch");
		event = MealFixture.kindId(admin, tenant, "Event");
		signInAs("uid-admin");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM staff_profiles");
		MealFixture.deleteAll(admin);
		admin.execute("DELETE FROM kitchens");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- Round trip -------------------------------------------------------------

	@Test
	@DisplayName("two kitchens with a dish under each round-trip: sections, dish kitchens, per-kitchen crew, meal crew = sum")
	void twoKitchensRoundTrip() throws Exception {
		UUID meal = save(lunch(kitchens(main, 4, sweets, 3), dish(khichdi, main), dish(payasam, sweets)));

		JsonNode view = read(get("/api/v1/meals/{id}", meal));
		assertThat(sectionIds(view)).containsExactly(main, sweets);
		assertThat(view.get("kitchens").get(0).get("kitchenName").asText()).isEqualTo("Main kitchen");
		assertThat(view.get("kitchens").get(0).get("isMain").asBoolean()).isTrue();
		assertThat(view.get("kitchens").get(0).get("crewRequired").asInt()).isEqualTo(4);
		assertThat(view.get("kitchens").get(1).get("isMain").asBoolean()).isFalse();
		assertThat(view.get("kitchens").get(1).get("crewRequired").asInt()).isEqualTo(3);
		assertThat(view.get("crewRequired").asInt()).as("the meal's figure is the sum").isEqualTo(7);
		assertThat(dishKitchen(view, "Khichdi")).isEqualTo(main);
		assertThat(dishKitchen(view, "Payasam")).isEqualTo(sweets);

		// What the rows say, not only what the view assembles.
		assertThat(admin.queryForObject("SELECT count(*) FROM meal_kitchens WHERE meal_id = ?", Integer.class, meal))
				.isEqualTo(2);
		assertThat(admin.queryForObject("""
				SELECT kitchen_id FROM meal_dishes WHERE meal_id = ? AND recipe_id = ?
				""", UUID.class, meal, payasam)).isEqualTo(sweets);

		// The trail records the kitchens as stored (lesson 2): read back from the rows.
		String after = admin.queryForObject("""
				SELECT after_state::text FROM audit_events
				WHERE entity_id = ? AND action = 'MEAL_PLANNED'
				""", String.class, meal);
		assertThat(after).contains("\"kitchens\"").contains("Sweets kitchen").contains("Main kitchen")
				.contains("\"crewRequired\": 3");
	}

	@Test
	@DisplayName("People needed of 0 or nothing is 'not said', and a meal where no kitchen has said has no figure")
	void zeroCrewIsNotSaid() throws Exception {
		UUID meal = save(lunch("""
				[{"kitchenId":"%s","crewRequired":null},{"kitchenId":"%s","crewRequired":0}]
				""".formatted(main, sweets), dish(khichdi, main)));

		JsonNode view = read(get("/api/v1/meals/{id}", meal));
		assertThat(view.get("crewRequired").isNull()).isTrue();
		assertThat(view.get("kitchens").get(0).get("crewRequired").isNull()).isTrue();
		assertThat(view.get("kitchens").get(1).get("crewRequired").isNull()).isTrue();
		// A section with no dishes yet is still a section.
		assertThat(sectionIds(view)).containsExactly(main, sweets);
	}

	// ---- No kitchens named ---------------------------------------------------------

	@Test
	@DisplayName("no kitchens named: the cook's meal goes to their own planning kitchen, the admin's to the main kitchen")
	void absentKitchensGoToTheSaversKitchen() throws Exception {
		signInAs("uid-cook");
		UUID cooks = save(lunch(null, "{\"recipeId\":\"%s\",\"targetYield\":10}".formatted(payasam)));
		JsonNode view = read(get("/api/v1/meals/{id}", cooks));
		assertThat(sectionIds(view)).containsExactly(sweets);
		assertThat(dishKitchen(view, "Payasam")).isEqualTo(sweets);

		signInAs("uid-admin");
		UUID admins = save("""
				{"planDate":"%s","mealKindId":"%s","readyBy":"19:00","adults":50,"ekadashiAcknowledged":false,
				 "dishes":[{"recipeId":"%s","targetYield":50}],"volunteerShift":null}
				""".formatted(DAY, MealFixture.kindId(admin, tenant, "Dinner"), khichdi));
		assertThat(sectionIds(read(get("/api/v1/meals/{id}", admins)))).containsExactly(main);
	}

	// ---- Refusals -----------------------------------------------------------------

	@Test
	@DisplayName("an empty list, and a temple with no kitchen planning meals, are KMS-400180; nothing is written")
	void noKitchenIsRefused() throws Exception {
		refused(lunch("[]", dish(khichdi, main)), 400, "KMS-400180");

		// No planner kitchen at all: nothing is created behind the Temple Admin's back.
		admin.update("UPDATE kitchens SET uses_meal_planner = false WHERE tenant_id = ?", tenant);
		refused(lunch(null, "{\"recipeId\":\"%s\",\"targetYield\":10}".formatted(khichdi)), 400, "KMS-400180");
		assertThat(admin.queryForObject("SELECT count(*) FROM kitchens WHERE tenant_id = ?", Integer.class, tenant))
				.as("no kitchen is seeded by a save").isEqualTo(4);
		assertThat(count("meals")).isZero();
	}

	@Test
	@DisplayName("an archived kitchen, and one not on the planner, are KMS-400181; an unknown one is KMS-400108")
	void kitchensThatDoNotPlanAreRefused() throws Exception {
		refused(lunch(kitchens(archived, null), dish(khichdi, archived)), 409, "KMS-400181");
		refused(lunch(kitchens(store, null), dish(khichdi, store)), 409, "KMS-400181");
		UUID nowhere = UUID.randomUUID();
		refused(lunch(kitchens(nowhere, null), dish(khichdi, nowhere)), 404, "KMS-400108");
		assertThat(count("meals")).isZero();
		assertThat(count("meal_kitchens")).isZero();
	}

	@Test
	@DisplayName("a kitchen twice, a dish under a kitchen not on the meal, and a dish with no kitchen among two, are KMS-400182")
	void dishKitchenNotOnMealIsRefused() throws Exception {
		refused(lunch("""
				[{"kitchenId":"%s","crewRequired":2},{"kitchenId":"%s","crewRequired":3}]
				""".formatted(main, main), dish(khichdi, main)), 400, "KMS-400182");
		refused(lunch(kitchens(main, 2), dish(khichdi, sweets)), 400, "KMS-400182");
		refused(lunch(kitchens(main, 2, sweets, 3),
				"{\"recipeId\":\"%s\",\"targetYield\":10}".formatted(khichdi)), 400, "KMS-400182");
		assertThat(count("meals")).isZero();
		assertThat(count("meal_dishes")).isZero();

		// With exactly one kitchen, a dish that names none goes to it.
		UUID meal = save(lunch(kitchens(sweets, 2), "{\"recipeId\":\"%s\",\"targetYield\":10}".formatted(khichdi)));
		assertThat(dishKitchen(read(get("/api/v1/meals/{id}", meal)), "Khichdi")).isEqualTo(sweets);
	}

	// ---- Editing -------------------------------------------------------------------

	@Test
	@DisplayName("an edit takes a kitchen off only when no dish sent is under it; its dish left out is cancelled")
	void updateRemovesAKitchenOnlyWhenEmpty() throws Exception {
		UUID meal = save(lunch(kitchens(main, 4, sweets, 3), dish(khichdi, main), dish(payasam, sweets)));
		UUID khichdiDish = dishId(meal, khichdi);
		UUID payasamDish = dishId(meal, payasam);

		// Sweets left out while payasam is still sent under it: refused, nothing changes.
		mvc.perform(authed(put("/api/v1/meals/{id}", meal)).contentType(MediaType.APPLICATION_JSON)
						.content(edit(kitchens(main, 5), kept(khichdiDish, khichdi, main), kept(payasamDish, payasam, sweets))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400182"));
		assertThat(admin.queryForObject("SELECT count(*) FROM meal_kitchens WHERE meal_id = ?", Integer.class, meal))
				.isEqualTo(2);

		// Sweets left out and payasam with it: Sweets goes, payasam is cancelled, the crew is Main's alone.
		mvc.perform(authed(put("/api/v1/meals/{id}", meal)).contentType(MediaType.APPLICATION_JSON)
						.content(edit(kitchens(main, 5), kept(khichdiDish, khichdi, main))))
				.andExpect(status().isOk());
		JsonNode view = read(get("/api/v1/meals/{id}", meal));
		assertThat(sectionIds(view)).containsExactly(main);
		assertThat(view.get("crewRequired").asInt()).isEqualTo(5);
		assertThat(admin.queryForObject("SELECT status FROM meal_dishes WHERE id = ?", String.class, payasamDish))
				.isEqualTo("CANCELLED");
		// The cancelled dish is kept as history, under the kitchen the edit kept.
		assertThat(admin.queryForObject("SELECT kitchen_id FROM meal_dishes WHERE id = ?", UUID.class, payasamDish))
				.isEqualTo(main);

		// A kitchen added and a kept dish moved into it in one edit.
		mvc.perform(authed(put("/api/v1/meals/{id}", meal)).contentType(MediaType.APPLICATION_JSON)
						.content(edit(kitchens(main, 5, sweets, 2), kept(khichdiDish, khichdi, sweets))))
				.andExpect(status().isOk());
		view = read(get("/api/v1/meals/{id}", meal));
		assertThat(sectionIds(view)).containsExactly(main, sweets);
		assertThat(dishKitchen(view, "Khichdi")).isEqualTo(sweets);
		assertThat(view.get("crewRequired").asInt()).isEqualTo(7);

		// An old-shaped edit, with no kitchens at all, leaves the kitchens and their figures alone and the
		// dish where it is.
		mvc.perform(authed(put("/api/v1/meals/{id}", meal)).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"readyBy":"12:30","adults":120,"ekadashiAcknowledged":false,
								 "dishes":[{"id":"%s","recipeId":"%s","targetYield":20}],"volunteerShift":null}
								""".formatted(khichdiDish, khichdi)))
				.andExpect(status().isOk());
		view = read(get("/api/v1/meals/{id}", meal));
		assertThat(sectionIds(view)).containsExactly(main, sweets);
		assertThat(view.get("crewRequired").asInt()).isEqualTo(7);
		assertThat(dishKitchen(view, "Khichdi")).isEqualTo(sweets);

		String after = admin.queryForObject("""
				SELECT after_state::text FROM audit_events
				WHERE entity_id = ? AND action = 'MEAL_PLAN_UPDATED' ORDER BY created_at DESC, id LIMIT 1
				""", String.class, meal);
		assertThat(after).contains("Sweets kitchen").contains("Khichdi 20");
	}

	// ---- Viewer order ----------------------------------------------------------------

	@Test
	@DisplayName("a cook in the Sweets kitchen sees Sweets first; the Temple Admin sees the main kitchen first")
	void sectionsComeInTheViewersOrder() throws Exception {
		UUID meal = save(lunch(kitchens(sweets, 3, main, 4), dish(khichdi, main), dish(payasam, sweets)));

		assertThat(sectionIds(read(get("/api/v1/meals/{id}", meal)))).containsExactly(main, sweets);
		assertThat(sectionIds(read(get("/api/v1/meals").param("from", DAY).param("to", DAY)).get(0)))
				.containsExactly(main, sweets);

		signInAs("uid-cook");
		assertThat(sectionIds(read(get("/api/v1/meals/{id}", meal)))).containsExactly(sweets, main);
		assertThat(sectionIds(read(get("/api/v1/meals").param("from", DAY).param("to", DAY)).get(0)))
				.containsExactly(sweets, main);
	}

	// ---- Copies ----------------------------------------------------------------------

	@Test
	@DisplayName("reusing a plan carries the kitchens, their figures and each dish's kitchen; a kitchen gone since is left behind and named")
	void reuseCarriesKitchens() throws Exception {
		save(lunch(kitchens(main, 4, sweets, 3), dish(khichdi, main), dish(payasam, sweets)));

		mvc.perform(authed(post("/api/v1/meal-plans/reuse")).contentType(MediaType.APPLICATION_JSON)
						.content("{\"sourceStart\":\"%s\",\"days\":1,\"targetStart\":\"2025-03-25\"}".formatted(DAY)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.copied").value(2));
		JsonNode copy = read(get("/api/v1/meals").param("from", "2025-03-25").param("to", "2025-03-25")).get(0);
		assertThat(sectionIds(copy)).containsExactly(main, sweets);
		assertThat(copy.get("kitchens").get(0).get("crewRequired").asInt()).isEqualTo(4);
		assertThat(copy.get("kitchens").get(1).get("crewRequired").asInt()).isEqualTo(3);
		assertThat(dishKitchen(copy, "Khichdi")).isEqualTo(main);
		assertThat(dishKitchen(copy, "Payasam")).isEqualTo(sweets);

		// The Sweets kitchen is archived: its dish cannot be copied, and the preview says why.
		admin.update("UPDATE kitchens SET status = 'ARCHIVED' WHERE id = ?", sweets);
		JsonNode preview = read(post("/api/v1/meal-plans/reuse/preview").contentType(MediaType.APPLICATION_JSON)
				.content("{\"sourceStart\":\"%s\",\"days\":1,\"targetStart\":\"2025-04-01\"}".formatted(DAY)));
		JsonNode landing = preview.get("days").get(0).get("meals");
		JsonNode sweet = landing.get(0).get("recipeName").asText().equals("Payasam") ? landing.get(0) : landing.get(1);
		assertThat(sweet.get("copied").asBoolean()).isFalse();
		assertThat(sweet.get("skippedReason").asText()).contains("Sweets kitchen").contains("no longer plans");

		mvc.perform(authed(post("/api/v1/meal-plans/reuse")).contentType(MediaType.APPLICATION_JSON)
						.content("{\"sourceStart\":\"%s\",\"days\":1,\"targetStart\":\"2025-04-01\"}".formatted(DAY)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.copied").value(1));
		JsonNode partial = read(get("/api/v1/meals").param("from", "2025-04-01").param("to", "2025-04-01")).get(0);
		assertThat(sectionIds(partial)).containsExactly(main);
	}

	@Test
	@DisplayName("repeating an event gives every copy the same kitchens with their figures, and each dish its kitchen")
	void repeatCarriesKitchens() throws Exception {
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
		LocalDate first = today.plusDays(1);
		UUID source = save("""
				{"planDate":"%s","mealKindId":"%s","readyBy":"17:00","eventName":"Gita reading","isOutside":false,
				 "ekadashiAcknowledged":false,"volunteerShift":null,
				 "kitchens":%s,
				 "dishes":[%s,%s]}
				""".formatted(first, event, kitchens(main, 2, sweets, 1), dish(khichdi, main), dish(payasam, sweets)));

		mvc.perform(authed(post("/api/v1/meals/{id}/repeat", source)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"everyWeeks\":1,\"until\":\"%s\"}".formatted(first.plusWeeks(2))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.copies").value(2));

		for (LocalDate on : List.of(first.plusWeeks(1), first.plusWeeks(2))) {
			JsonNode copy = read(get("/api/v1/meals").param("from", on.toString()).param("to", on.toString())).get(0);
			assertThat(sectionIds(copy)).as("sections on %s", on).containsExactly(main, sweets);
			assertThat(copy.get("kitchens").get(0).get("crewRequired").asInt()).isEqualTo(2);
			assertThat(copy.get("kitchens").get(1).get("crewRequired").asInt()).isEqualTo(1);
			assertThat(copy.get("crewRequired").asInt()).isEqualTo(3);
			assertThat(dishKitchen(copy, "Payasam")).isEqualTo(sweets);
		}

		// A kitchen gone since: the repeat is refused, naming no date, rather than silently dropping a dish.
		admin.update("UPDATE kitchens SET uses_meal_planner = false WHERE id = ?", sweets);
		mvc.perform(authed(post("/api/v1/meals/{id}/repeat", source)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"everyWeeks\":1,\"until\":\"%s\"}".formatted(first.plusWeeks(4))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400181"));
	}

	// ---- Tenant isolation -------------------------------------------------------------

	@Test
	@DisplayName("another temple sees none of this temple's meal kitchens and cannot name its kitchen")
	void anotherTempleCannotSeeTheSections() throws Exception {
		UUID meal = save(lunch(kitchens(main, 4, sweets, 3), dish(khichdi, main), dish(payasam, sweets)));

		UUID other = temple("other-temple");
		UUID otherAdmin = user(other, "uid-other", "other@example.com", "TEMPLE_ADMIN", "+919876500499");
		kitchen(other, "Their kitchen", true, true, "ACTIVE", otherAdmin);
		seedKinds(other);
		signInAs("uid-other");

		mvc.perform(authed(get("/api/v1/meals/{id}", meal))).andExpect(status().isNotFound());
		assertThat(read(get("/api/v1/meals").param("from", DAY).param("to", DAY)).size()).isZero();

		// Naming this temple's kitchen from the other: not found, because row-level security hides it.
		mvc.perform(authed(post("/api/v1/meals")).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"planDate":"%s","mealKindId":"%s","readyBy":"12:00","adults":10,
								 "ekadashiAcknowledged":false,"volunteerShift":null,
								 "kitchens":[{"kitchenId":"%s","crewRequired":1}],
								 "dishes":[{"recipeId":"%s","targetYield":10,"kitchenId":"%s"}]}
								""".formatted(DAY, MealFixture.kindId(admin, other, "Lunch"), main, khichdi, main)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("KMS-400108"));
		assertThat(admin.queryForObject("SELECT count(*) FROM meal_kitchens WHERE tenant_id = ?", Integer.class, other))
				.isZero();
	}

	// ---- Helpers ------------------------------------------------------------------------

	private String lunch(String kitchens, String... dishes) {
		return """
				{"planDate":"%s","mealKindId":"%s","readyBy":"12:00","adults":100,"ekadashiAcknowledged":false,
				 "volunteerShift":null,%s
				 "dishes":[%s]}
				""".formatted(DAY, lunch, kitchens == null ? "" : "\"kitchens\":" + kitchens + ",",
				String.join(",", dishes));
	}

	private String edit(String kitchens, String... dishes) {
		return """
				{"readyBy":"12:00","adults":100,"ekadashiAcknowledged":false,"volunteerShift":null,
				 "kitchens":%s,"dishes":[%s]}
				""".formatted(kitchens, String.join(",", dishes));
	}

	private static String kitchens(UUID kitchen, Integer crew) {
		return "[{\"kitchenId\":\"%s\",\"crewRequired\":%s}]".formatted(kitchen, crew);
	}

	private static String kitchens(UUID a, Integer crewA, UUID b, Integer crewB) {
		return "[{\"kitchenId\":\"%s\",\"crewRequired\":%s},{\"kitchenId\":\"%s\",\"crewRequired\":%s}]"
				.formatted(a, crewA, b, crewB);
	}

	private static String dish(UUID recipe, UUID kitchen) {
		return "{\"recipeId\":\"%s\",\"targetYield\":10,\"kitchenId\":\"%s\"}".formatted(recipe, kitchen);
	}

	private static String kept(UUID dishId, UUID recipe, UUID kitchen) {
		return "{\"id\":\"%s\",\"recipeId\":\"%s\",\"targetYield\":10,\"kitchenId\":\"%s\"}"
				.formatted(dishId, recipe, kitchen);
	}

	private UUID save(String body) throws Exception {
		String response = mvc.perform(authed(post("/api/v1/meals")).contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return MealRequests.idOf(response);
	}

	private ResultActions refused(String body, int status, String code) throws Exception {
		return mvc.perform(authed(post("/api/v1/meals")).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().is(status))
				.andExpect(jsonPath("$.code").value(code));
	}

	private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
		return JSON.readTree(mvc.perform(authed(request)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
	}

	private static List<UUID> sectionIds(JsonNode meal) {
		List<UUID> ids = new ArrayList<>();
		meal.get("kitchens").forEach(k -> ids.add(UUID.fromString(k.get("kitchenId").asText())));
		return ids;
	}

	private static UUID dishKitchen(JsonNode meal, String recipeName) {
		for (JsonNode dish : meal.get("dishes")) {
			if (dish.get("recipeName").asText().equals(recipeName) && !"CANCELLED".equals(dish.get("status").asText())) {
				return UUID.fromString(dish.get("kitchenId").asText());
			}
		}
		throw new AssertionError("No live dish " + recipeName + " in " + meal);
	}

	private UUID dishId(UUID meal, UUID recipe) {
		return admin.queryForObject("SELECT id FROM meal_dishes WHERE meal_id = ? AND recipe_id = ?",
				UUID.class, meal, recipe);
	}

	private int count(String table) {
		return admin.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenant);
	}

	private void signInAs(String uid) {
		stubVerifier.accept(uid);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	private UUID temple(String slug) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, 'Test Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug);
	}

	private UUID user(UUID temple, String uid, String email, String role, String phone) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, ?, ?, 'ACTIVE') RETURNING id
				""", UUID.class, temple, uid, email, phone, role);
	}

	private UUID kitchen(UUID temple, String name, boolean isMain, boolean planner, String status, UUID by) {
		return admin.queryForObject("""
				INSERT INTO kitchens (tenant_id, name, is_main, uses_meal_planner, status, created_by)
				VALUES (?, ?, ?, ?, ?, ?) RETURNING id
				""", UUID.class, temple, name, isMain, planner, status, by);
	}

	private UUID recipe(UUID temple, String name, UUID category) {
		return admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, ?, ?, 100, 'KG') RETURNING id
				""", UUID.class, temple, name, category);
	}

	private void seedKinds(UUID temple) {
		TenantContext.set(temple);
		try {
			mealKindService.seedForCurrentTenant();
		} finally {
			TenantContext.clear();
		}
	}
}
