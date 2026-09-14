package org.iskcon.kms.meal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Renaming a kind of meal, and what deleting one means (T-038, rebuilt on meal rows by D-27).
 *
 * <p><strong>What this class used to prove, and why it proves the opposite now.</strong> Until D-27 a
 * meal did not reference its kind: it stored the name, in the dish rows, the recorded meals and the
 * volunteer shifts linked to a meal, and a rename had to be carried through all three by hand —
 * case-insensitively, because one of the three writers stored whatever the caller typed. This class
 * proved that cascade worked. A meal points at its kind by id now ({@code meals.meal_kind_id}), a
 * shift points at its meal, and the cascade is gone. So the claim here is the one the foreign key
 * makes true: <strong>a rename writes one row</strong> — the kind's — and every reader that shows a
 * meal's kind shows the new name, because it reads the name through the key at the moment it reads.
 *
 * <p>Those readers are asked through the paths that used to break: the reuse preview, the job card's
 * language picker, the meal view and the meal's volunteer shift. And the rows that used to be
 * rewritten are asserted untouched, by their {@code updated_at}.
 *
 * <p><strong>Two temples, always.</strong> RLS is what confines the rename, and an assertion that it
 * is confined is worthless unless a second temple holds a kind of the same name, with meals under it.
 *
 * <p><strong>Deleting</strong> is refused for a kind any meal points at — planned, cancelled, or
 * holding nothing but a volunteer shift — and that refusal is now the database's
 * ({@code ON DELETE RESTRICT}), answered as KMS-400126.
 */
@AutoConfigureMockMvc
class MealKindIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** A Monday, and nothing turns on that. */
	private static final String DATE = "2026-09-07";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private MealKindService mealKindService;

	@Autowired
	private StubTokenVerifier stubVerifier;

	// The meal's shift may schedule its reminders; the scheduler itself is not what this is about.
	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;

	private UUID tenant;
	private UUID khichdi;

	/** The second temple: same seeded kinds, same names, entirely separate rows. */
	private UUID otherTenant;
	private UUID otherUser;
	private UUID otherRecipe;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());

		tenant = insertTenant("rename-temple", "Bengaluru Temple");
		insertUser(tenant, "uid-admin", "TEMPLE_ADMIN", "+919876500001");
		insertUser(tenant, "uid-vol-1", "VOLUNTEER", "+919876500091");
		khichdi = insertRecipe(tenant, "Khichdi");

		otherTenant = insertTenant("other-temple", "Mysuru Temple");
		otherUser = insertUser(otherTenant, "uid-other", "TEMPLE_ADMIN", "+919876500002");
		otherRecipe = insertRecipe(otherTenant, "Payasam");

		seedKinds(tenant);
		seedKinds(otherTenant);

		stubVerifier.accept("uid-admin");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM shift_waitlist");
		admin.execute("DELETE FROM shift_signups");
		admin.execute("DELETE FROM shifts");
		MealFixture.deleteAll(admin);
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- A rename writes one row ------------------------------------------

	@Test
	@DisplayName("a rename writes the kind's own row and nothing else, and every reader shows the new name")
	void aRenameIsReadThroughTheKey() throws Exception {
		UUID meal = planWithShift("Lunch");
		UUID dish = admin.queryForObject("SELECT id FROM meal_dishes WHERE meal_id = ?", UUID.class, meal);
		UUID shift = admin.queryForObject("SELECT id FROM shifts WHERE meal_id = ?", UUID.class, meal);
		signUp(shift, "uid-vol-1");

		OffsetDateTime mealBefore = updatedAt("meals", meal);
		OffsetDateTime dishBefore = updatedAt("meal_dishes", dish);
		OffsetDateTime shiftBefore = updatedAt("shifts", shift);

		rename(kindId(tenant, "Lunch"), "Prasadam lunch", 20, "12:00");

		// 1. The reuse preview, which used to resolve every stored name and throw KMS-400071 on one a
		//    rename had missed. It reads the kind by id now.
		mvc.perform(authed(post("/api/v1/meal-plans/reuse/preview"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"sourceStart":"%s","days":1,"targetStart":"2026-09-21"}
								""".formatted(DATE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kinds.length()").value(1))
				.andExpect(jsonPath("$.kinds[0].mealKind").value("Prasadam lunch"))
				.andExpect(jsonPath("$.totals.meals").value(1));

		// 2. The job card's language picker, which used to find the meal by its kind's name. The meal
		//    is asked for by its id, so there is no old name for it to go looking for.
		mvc.perform(authed(get("/api/v1/job-cards/languages")).param("mealId", meal.toString()))
				.andExpect(status().isOk());

		// 3. The meal itself, and the shift for it, both under the new name — and the volunteer is still
		//    on that shift, because nothing about the link was ever a name.
		mvc.perform(authed(get("/api/v1/meals/{id}", meal)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mealKind").value("Prasadam lunch"))
				.andExpect(jsonPath("$.volunteerShift.mealKind").value("Prasadam lunch"))
				.andExpect(jsonPath("$.volunteerShift.signedUpCount").value(1));

		// And not one of the rows the cascade used to rewrite was written.
		assertThat(updatedAt("meals", meal)).isEqualTo(mealBefore);
		assertThat(updatedAt("meal_dishes", dish)).isEqualTo(dishBefore);
		assertThat(updatedAt("shifts", shift)).isEqualTo(shiftBefore);
	}

	@Test
	@DisplayName("the other temple's Lunch, and everything under it, is untouched")
	void theRenameStopsAtTheTenantBoundary() throws Exception {
		plan("Lunch");

		// The second temple has its own Lunch, a meal under it and a shift for that meal. Seeded
		// privileged, because a fixture spanning two tenants cannot be built through a connection
		// scoped to one.
		UUID otherMeal = MealFixture.meal(admin, otherTenant, LocalDate.parse(DATE), "Lunch", LocalTime.NOON);
		MealFixture.dish(admin, otherTenant, otherMeal, otherRecipe, BigDecimal.valueOf(200), otherUser);
		UUID otherShift = insertShiftAs(otherTenant, otherUser, otherMeal);
		OffsetDateTime otherMealBefore = updatedAt("meals", otherMeal);

		rename(kindId(tenant, "Lunch"), "Prasadam lunch", 20, "12:00");

		assertThat(admin.queryForObject(
				"SELECT name FROM meal_kinds WHERE id = ?", String.class, kindId(otherTenant, "Lunch")))
				.isEqualTo("Lunch");
		assertThat(admin.queryForObject("""
				SELECT k.name FROM meals m JOIN meal_kinds k ON k.id = m.meal_kind_id WHERE m.id = ?
				""", String.class, otherMeal)).isEqualTo("Lunch");
		assertThat(updatedAt("meals", otherMeal)).isEqualTo(otherMealBefore);
		assertThat(admin.queryForObject("SELECT meal_id FROM shifts WHERE id = ?", UUID.class, otherShift))
				.isEqualTo(otherMeal);

		// And the temple that did rename is the one whose meal reads the new name.
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM meals m JOIN meal_kinds k ON k.id = m.meal_kind_id
				WHERE m.tenant_id = ? AND k.name = 'Prasadam lunch'
				""", Integer.class, tenant)).isEqualTo(1);
	}

	@Test
	@DisplayName("an edit that leaves the name alone changes the kind and writes nothing under it")
	void anEditThatIsNotARenameTouchesNothing() throws Exception {
		UUID meal = planWithShift("Lunch");
		UUID shift = admin.queryForObject("SELECT id FROM shifts WHERE meal_id = ?", UUID.class, meal);
		OffsetDateTime mealBefore = updatedAt("meals", meal);
		OffsetDateTime shiftBefore = updatedAt("shifts", shift);

		// The common edit, and the one the screen exists for: the time lunch is due moves by half an
		// hour.
		rename(kindId(tenant, "Lunch"), "Lunch", 20, "12:30");

		assertThat(updatedAt("meals", meal)).isEqualTo(mealBefore);
		assertThat(updatedAt("shifts", shift)).isEqualTo(shiftBefore);
		assertThat(admin.queryForObject(
				"SELECT default_ready_time::text FROM meal_kinds WHERE id = ?",
				String.class, kindId(tenant, "Lunch")))
				.isEqualTo("12:30:00");
	}

	// ---- Renaming onto a name the temple already has ----------------------

	@Test
	@DisplayName("renaming a kind onto an existing name is a typo, answered with KMS-400047")
	void aRenameOntoAnExistingNameIsRefused() throws Exception {
		// Bengaluru has all six seeded kinds. Somebody editing Lunch types Dinner.
		mvc.perform(renameRequest(kindId(tenant, "Lunch"), "Dinner", 20, "12:00"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400047"))
				.andExpect(jsonPath("$.message").value("That kind of meal already exists."));

		// Not a 500, and not a half-applied edit either.
		assertThat(admin.queryForObject(
				"SELECT name FROM meal_kinds WHERE id = ?", String.class, kindId(tenant, "Lunch")))
				.isEqualTo("Lunch");
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM meal_kinds WHERE tenant_id = ? AND lower(name) = 'dinner'
				""", Integer.class, tenant)).isEqualTo(1);
	}

	@Test
	@DisplayName("the collision is case-insensitive, because the index is on lower(name)")
	void aRenameOntoAnExistingNameInAnotherCaseIsRefused() throws Exception {
		mvc.perform(renameRequest(kindId(tenant, "Lunch"), "DINNER", 20, "12:00"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400047"));

		assertThat(admin.queryForObject(
				"SELECT name FROM meal_kinds WHERE id = ?", String.class, kindId(tenant, "Lunch")))
				.isEqualTo("Lunch");
		assertThat(admin.queryForObject(
				"SELECT name FROM meal_kinds WHERE id = ?", String.class, kindId(tenant, "Dinner")))
				.isEqualTo("Dinner");
	}

	@Test
	@DisplayName("a kind keeping its own name, in the same case or a new one, is not a collision")
	void aKindMayKeepOrRecaseItsOwnName() throws Exception {
		UUID meal = plan("Lunch");

		rename(kindId(tenant, "Lunch"), "Lunch", 20, "12:30");

		// And a case-only edit is a real rename: "LUNCH" is what every screen will print, and it does,
		// because every screen reads the name through the kind's id.
		rename(kindId(tenant, "Lunch"), "LUNCH", 20, "12:30");

		assertThat(admin.queryForObject(
				"SELECT name FROM meal_kinds WHERE id = ?", String.class, kindId(tenant, "Lunch")))
				.isEqualTo("LUNCH");
		mvc.perform(authed(get("/api/v1/meals/{id}", meal)))
				.andExpect(jsonPath("$.mealKind").value("LUNCH"));
	}

	// ---- Deleting a kind that is in use -----------------------------------

	@Test
	@DisplayName("a kind a meal has been planned under cannot be deleted")
	void aPlannedMealHoldsItsKind() throws Exception {
		plan("Lunch");

		mvc.perform(authed(delete("/api/v1/meal-kinds/{id}", kindId(tenant, "Lunch"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400126"));

		assertThat(kindId(tenant, "Lunch")).isNotNull();
	}

	@Test
	@DisplayName("a kind whose only meal was cancelled cannot be deleted either")
	void aCancelledMealHoldsItsKind() {
		// A cancelled meal is still a meal the temple decided on, and it is the row a cancelled Lunch
		// planned again comes back to (D-27). Deleting its kind would take that row with it.
		UUID meal = MealFixture.meal(admin, tenant, LocalDate.parse(DATE), "Dinner", LocalTime.of(19, 30));
		MealFixture.dish(admin, tenant, meal, khichdi, BigDecimal.valueOf(100), "CANCELLED", adminUser());

		assertRefusesDelete(kindId(tenant, "Dinner"));
	}

	@Test
	@DisplayName("a kind whose meal holds nothing but a volunteer shift cannot be deleted")
	void aMealWithOnlyAShiftHoldsItsKind() {
		// No dish at all — only a shift pointing at the meal. Before D-27 this was the case a check
		// written from the planner's side missed; the chain of keys (shift → meal → kind) is what holds
		// it now, whoever deletes.
		UUID meal = MealFixture.meal(admin, tenant, LocalDate.parse(DATE), "Dinner", LocalTime.of(19, 30));
		insertShiftAs(tenant, adminUser(), meal);

		assertRefusesDelete(kindId(tenant, "Dinner"));
	}

	@Test
	@DisplayName("a kind nothing has ever used is still deleted")
	void anUnusedKindGoes() throws Exception {
		String body = mvc.perform(authed(post("/api/v1/meal-kinds"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Midnight snack","sortOrder":70,"defaultReadyTime":null,
								 "isEvent":false,"needsOccasion":false}
								"""))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(JSON.readTree(body).get("id").asText());

		mvc.perform(authed(delete("/api/v1/meal-kinds/{id}", id)))
				.andExpect(status().isNoContent());

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM meal_kinds WHERE id = ?", Integer.class, id)).isZero();
	}

	@Test
	@DisplayName("the other temple's use of the name does not hold this temple's kind")
	void useIsCountedPerTemple() throws Exception {
		// Mysuru has planned a lunch. Bengaluru has not, and its own Lunch is a different row that no
		// meal points at.
		UUID otherMeal = MealFixture.meal(admin, otherTenant, LocalDate.parse(DATE), "Lunch", LocalTime.NOON);
		MealFixture.dish(admin, otherTenant, otherMeal, otherRecipe, BigDecimal.valueOf(200), otherUser);

		mvc.perform(authed(delete("/api/v1/meal-kinds/{id}", kindId(tenant, "Lunch"))))
				.andExpect(status().isNoContent());

		assertThat(kindId(otherTenant, "Lunch")).isNotNull();
	}

	// ---- helpers ----------------------------------------------------------

	private void assertRefusesDelete(UUID kindId) {
		try {
			mvc.perform(authed(delete("/api/v1/meal-kinds/{id}", kindId)))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("KMS-400126"))
					.andExpect(jsonPath("$.message")
							.value("Meals have already been planned or recorded as this kind."));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		assertThat(admin.queryForObject(
				"SELECT count(*) FROM meal_kinds WHERE id = ?", Integer.class, kindId)).isEqualTo(1);
	}

	private void rename(UUID id, String name, int sortOrder, String readyTime) throws Exception {
		mvc.perform(renameRequest(id, name, sortOrder, readyTime))
				.andExpect(status().isNoContent());
	}

	private MockHttpServletRequestBuilder renameRequest(
			UUID id, String name, int sortOrder, String readyTime) {
		return authed(put("/api/v1/meal-kinds/{id}", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name":"%s","sortOrder":%d,"defaultReadyTime":"%s","isEvent":false,
						 "needsOccasion":false}
						""".formatted(name, sortOrder, readyTime));
	}

	/** Plans the kind's meal through the endpoint, and answers with the meal's id. */
	private UUID plan(String kind) throws Exception {
		return save("""
				{"planDate":"%s","mealKindId":"%s","adults":200,"crewRequired":4,
				 "dishes":[{"recipeId":"%s","targetYield":200}]}
				""".formatted(DATE, kindId(tenant, kind), khichdi));
	}

	/** The same, with a volunteer shift asked for from the planner (D-27). */
	private UUID planWithShift(String kind) throws Exception {
		return save("""
				{"planDate":"%s","mealKindId":"%s","adults":200,"crewRequired":4,
				 "dishes":[{"recipeId":"%s","targetYield":200}],
				 "volunteerShift":{"title":"Cut vegetables","startTime":"06:00","endTime":"10:00","capacity":6}}
				""".formatted(DATE, kindId(tenant, kind), khichdi));
	}

	private UUID save(String json) throws Exception {
		String body = mvc.perform(authed(post("/api/v1/meals")).contentType(MediaType.APPLICATION_JSON)
						.content(json))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return MealRequests.idOf(body);
	}

	private void signUp(UUID shiftId, String volunteerUid) {
		admin.update("""
				INSERT INTO shift_signups (tenant_id, shift_id, volunteer_user_id)
				VALUES (?, ?, (SELECT id FROM users WHERE firebase_uid = ?))
				""", tenant, shiftId, volunteerUid);
	}

	private UUID kindId(UUID forTenant, String name) {
		return admin.queryForObject(
				"SELECT id FROM meal_kinds WHERE tenant_id = ? AND lower(name) = lower(?)",
				UUID.class, forTenant, name);
	}

	private UUID adminUser() {
		return admin.queryForObject("SELECT id FROM users WHERE firebase_uid = 'uid-admin'", UUID.class);
	}

	private OffsetDateTime updatedAt(String table, UUID id) {
		return admin.queryForObject(
				"SELECT updated_at FROM " + table + " WHERE id = ?", OffsetDateTime.class, id);
	}

	private void seedKinds(UUID forTenant) {
		TenantContext.set(forTenant);
		try {
			mealKindService.seedForCurrentTenant();
		} finally {
			TenantContext.clear();
		}
	}

	// --- privileged fixture setup, for the rows the second temple owns -------

	private UUID insertTenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);
	}

	private UUID insertUser(UUID forTenant, String uid, String role, String phone) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, ?, ?, 'ACTIVE')
				RETURNING id
				""", UUID.class, forTenant, uid, uid + "@example.com", phone, role);
	}

	private UUID insertRecipe(UUID forTenant, String name) {
		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Rice') RETURNING id
				""", UUID.class, forTenant);
		return admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, ?, ?, 100, 'KG') RETURNING id
				""", UUID.class, forTenant, name, category);
	}

	private UUID insertShiftAs(UUID forTenant, UUID user, UUID mealId) {
		return admin.queryForObject("""
				INSERT INTO shifts (tenant_id, title, shift_date, start_time, end_time, capacity,
						created_by, meal_id)
				VALUES (?, 'Lunch prep', ?::date, '06:00', '10:00', 6, ?, ?)
				RETURNING id
				""", UUID.class, forTenant, DATE, user, mealId);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

}
