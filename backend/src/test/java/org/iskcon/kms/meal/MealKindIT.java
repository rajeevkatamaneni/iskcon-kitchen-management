package org.iskcon.kms.meal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Renaming a kind of meal, and what deleting one means (T-038).
 *
 * <p>A meal does not <em>reference</em> its kind. It <em>stores the name</em>, in three separate
 * tables — {@code meal_plans.meal_kind}, {@code meal_services.meal_kind} (V64) and
 * {@code shifts.meal_kind} (V95) — because {@code meal_kinds} is unique on an expression index over
 * {@code (tenant_id, lower(name))} and PostgreSQL will not take an expression index as a foreign key
 * target. Nothing in the database therefore keeps those three columns honest, and until this change
 * nothing in the application did either.
 *
 * <p>Both halves of that are proved here, and both are proved <strong>through paths that resolve the
 * stored name</strong> rather than by reading the column back. Selecting the column back would pass
 * against a cascade that was subtly wrong — the wrong case, say — because the failure is not in the
 * write, it is in what {@code MealKindService.require} makes of it afterwards. So the rename is
 * checked by walking a reuse preview, asking a meal what languages its job card prints in, and
 * counting the crew a linked shift brings; and the delete is checked by the refusal that now stops
 * any of those three from being armed to fail in the first place.
 *
 * <p><strong>Two temples, always.</strong> The cascade carries no {@code tenant_id} and must not: it
 * is Row-Level Security that confines it, and an assertion that it is confined is worthless unless
 * there is a second temple, holding a kind of the same name, for it to have escaped into. The second
 * temple here is seeded with a Lunch, a lunch plan, a lunch service row and a lunch-linked shift,
 * and every one of them is asserted untouched.
 *
 * <p><strong>T-047</strong> adds the third thing a rename can do: land on a name the temple already
 * has. That was a 500 — {@code update()} did not catch the {@code DuplicateKeyException} that
 * {@code create()} has always caught — and it is answered here as the typo it is, with
 * KMS-400047. The collision is case-insensitive because {@code meal_kinds_name_per_tenant} is over
 * {@code (tenant_id, lower(name))}, so "DINNER" is the temple's "Dinner"; and a kind keeping or
 * re-casing its own name is not a collision, which is asserted rather than assumed.
 */
@AutoConfigureMockMvc
@Import(MealKindIT.StubVerifierConfiguration.class)
class MealKindIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** A Monday, and nothing turns on that. */
	private static final String DATE = "2026-09-07";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private MealKindService mealKindService;

	@Autowired
	private ServedMealService servedMealService;

	@Autowired
	private StubTokenVerifier stubVerifier;

	// Shift creation schedules its reminders; the scheduler itself is not what this is about.
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
		stubVerifier.reset();

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
		admin.execute("DELETE FROM meal_services");
		admin.execute("DELETE FROM meal_card_sequence");
		admin.execute("DELETE FROM meal_plans");
		admin.execute("DELETE FROM meal_kinds");
		admin.execute("DELETE FROM shift_waitlist");
		admin.execute("DELETE FROM shift_signups");
		admin.execute("DELETE FROM shifts");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- The rename cascades ----------------------------------------------

	@Test
	@DisplayName("a rename carries every plan, recorded meal and linked shift with it")
	void aRenameCarriesEverythingRecordedUnderTheOldName() throws Exception {
		plan("Lunch");
		UUID serviceId = serviceFor(tenant, DATE, "Lunch");

		// Linked in lower case, which is not a contrivance: ShiftService stores shifts.meal_kind as
		// the caller typed it — trimToNull(request.mealKind()), never routed through the kind service
		// — and MealMoment folds both sides when it matches. So "lunch" against a temple that calls
		// it "Lunch" is a supported, working link today (ShiftMealLinkIT proves it), and it is exactly
		// the row an exact-match cascade would leave behind pointing at a kind that no longer exists.
		String shift = createShift("""
				{"title":"Cut vegetables for lunch","shiftDate":"%s","startTime":"06:00","endTime":"10:00",
				 "capacity":6,"mealDate":"%s","mealKind":"lunch"}
				""".formatted(DATE, DATE));
		signUp(shift, "uid-vol-1");

		rename(kindId(tenant, "Lunch"), "Prasadam lunch", 20, "12:00");

		// 1. The reuse preview. It walks historical meal_plans rows and resolves every distinct stored
		//    kind through require(), so an uncascaded plan makes the whole preview throw KMS-400071 —
		//    a screen breaking days later, nowhere near the settings page that broke it.
		mvc.perform(authed(post("/api/v1/meal-plans/reuse/preview"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"sourceStart":"%s","days":1,"targetStart":"2026-09-21"}
								""".formatted(DATE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kinds.length()").value(1))
				.andExpect(jsonPath("$.kinds[0].mealKind").value("Prasadam lunch"))
				.andExpect(jsonPath("$.totals.meals").value(1));

		// 2. The job card's language picker, which reaches ServedMealService.find and resolves the
		//    kind on the way in. The meal is found under its new name, and the old one is gone.
		mvc.perform(authed(get("/api/v1/job-cards/languages"))
						.param("date", DATE).param("mealKind", "Prasadam lunch"))
				.andExpect(status().isOk());
		mvc.perform(authed(get("/api/v1/job-cards/languages"))
						.param("date", DATE).param("mealKind", "Lunch"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400071"));

		// 3. The crew count, which is the only thing that reads a shift's link. The volunteer still
		//    lands on this meal, under its new name — the link survived the rename.
		mvc.perform(authed(get("/api/v1/meal-crew")).param("from", DATE).param("to", DATE))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].mealKind").value("Prasadam lunch"))
				.andExpect(jsonPath("$[0].volunteers").value(1));

		// And the meal's own row moved with it, which is what keeps the recording and the card number
		// attached to the meal rather than stranded on a name nothing answers to.
		assertThat(admin.queryForObject(
				"SELECT meal_kind FROM meal_services WHERE id = ?", String.class, serviceId))
				.isEqualTo("Prasadam lunch");
		assertThat(admin.queryForObject(
				"SELECT meal_kind FROM shifts WHERE id = ?::uuid", String.class, shift))
				.isEqualTo("Prasadam lunch");
	}

	@Test
	@DisplayName("the other temple's Lunch, and everything under it, is untouched")
	void theRenameStopsAtTheTenantBoundary() throws Exception {
		plan("Lunch");
		serviceFor(tenant, DATE, "Lunch");

		// The second temple has its own Lunch and its own rows under it, with the same name in all
		// three tables. Seeded privileged, because a fixture spanning two tenants cannot be built
		// through a connection that is scoped to one.
		UUID otherPlan = insertPlanAs(otherTenant, otherRecipe, otherUser, "Lunch");
		UUID otherService = insertServiceAs(otherTenant, "Lunch");
		UUID otherShift = insertShiftAs(otherTenant, otherUser, "Lunch");

		rename(kindId(tenant, "Lunch"), "Prasadam lunch", 20, "12:00");

		// Nothing carrying no tenant_id ran across the boundary: the policy held.
		assertThat(admin.queryForObject(
				"SELECT name FROM meal_kinds WHERE id = ?", String.class, kindId(otherTenant, "Lunch")))
				.isEqualTo("Lunch");
		assertThat(admin.queryForObject(
				"SELECT meal_kind FROM meal_plans WHERE id = ?", String.class, otherPlan))
				.isEqualTo("Lunch");
		assertThat(admin.queryForObject(
				"SELECT meal_kind FROM meal_services WHERE id = ?", String.class, otherService))
				.isEqualTo("Lunch");
		assertThat(admin.queryForObject(
				"SELECT meal_kind FROM shifts WHERE id = ?", String.class, otherShift))
				.isEqualTo("Lunch");

		// And the temple that did rename is the one that changed.
		assertThat(admin.queryForObject("""
				SELECT count(*) FROM meal_plans WHERE tenant_id = ? AND meal_kind = 'Prasadam lunch'
				""", Integer.class, tenant)).isEqualTo(1);
	}

	@Test
	@DisplayName("an edit that leaves the name alone writes nothing to the three tables")
	void anEditThatIsNotARenameTouchesNothing() throws Exception {
		plan("Lunch");
		UUID serviceId = serviceFor(tenant, DATE, "Lunch");
		String shift = createShift("""
				{"title":"Lunch prep","shiftDate":"%s","startTime":"06:00","endTime":"10:00",
				 "capacity":6,"mealDate":"%s","mealKind":"Lunch"}
				""".formatted(DATE, DATE));

		OffsetDateTime planBefore = updatedAt("meal_plans", planId());
		OffsetDateTime serviceBefore = updatedAt("meal_services", serviceId);
		OffsetDateTime shiftBefore = updatedAt("shifts", UUID.fromString(shift));

		// The common edit, and the one the screen exists for: the time lunch is due moves by half an
		// hour. Three tables must not be rewritten for it.
		rename(kindId(tenant, "Lunch"), "Lunch", 20, "12:30");

		assertThat(updatedAt("meal_plans", planId())).isEqualTo(planBefore);
		assertThat(updatedAt("meal_services", serviceId)).isEqualTo(serviceBefore);
		assertThat(updatedAt("shifts", UUID.fromString(shift))).isEqualTo(shiftBefore);

		// The kind itself did change, which is the point of the request.
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

		// Not a 500, and not a half-applied edit either: the failed UPDATE is rolled back with the
		// rest of the transaction, so Lunch is still Lunch and still due at noon.
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
		// meal_kinds_name_per_tenant is over (tenant_id, lower(name)) — V22 as
		// meal_slots_name_per_tenant, renamed by V48 — so "DINNER" is the temple's "Dinner" and the
		// database refuses it. An exact-match pre-check in Java would have let this through and the
		// person would have met a 500 instead.
		mvc.perform(renameRequest(kindId(tenant, "Lunch"), "DINNER", 20, "12:00"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400047"));

		assertThat(admin.queryForObject(
				"SELECT name FROM meal_kinds WHERE id = ?", String.class, kindId(tenant, "Lunch")))
				.isEqualTo("Lunch");
		// And the kind that was collided with is untouched — it was not re-cased by the attempt.
		assertThat(admin.queryForObject(
				"SELECT name FROM meal_kinds WHERE id = ?", String.class, kindId(tenant, "Dinner")))
				.isEqualTo("Dinner");
	}

	@Test
	@DisplayName("a kind keeping its own name, in the same case or a new one, is not a collision")
	void aKindMayKeepOrRecaseItsOwnName() throws Exception {
		plan("Lunch");

		// The row's own index entry is the one being rewritten, so rewriting it to the value it
		// already holds conflicts with nothing. This is the ordinary save of the settings screen —
		// somebody changed the ready-by time and left the name alone.
		rename(kindId(tenant, "Lunch"), "Lunch", 20, "12:30");

		// And a case-only edit is a real rename, not a no-op: "LUNCH" is what every screen will
		// print, so it must be accepted and must carry the stored copies with it.
		rename(kindId(tenant, "Lunch"), "LUNCH", 20, "12:30");

		assertThat(admin.queryForObject(
				"SELECT name FROM meal_kinds WHERE id = ?", String.class, kindId(tenant, "Lunch")))
				.isEqualTo("LUNCH");
		assertThat(admin.queryForObject(
				"SELECT meal_kind FROM meal_plans WHERE id = ?", String.class, planId()))
				.isEqualTo("LUNCH");
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
	@DisplayName("a kind only a recorded meal still uses cannot be deleted")
	void aRecordedMealHoldsItsKind() {
		// A meal service row with no plan left beside it — the plans were cancelled and cleared, the
		// record of what went out that day was not. Deleting the kind now would break the job card
		// this row exists to let somebody reprint.
		insertServiceAs(tenant, "Dinner");

		assertRefusesDelete(kindId(tenant, "Dinner"));
	}

	@Test
	@DisplayName("a kind only a linked shift uses cannot be deleted — the case a naive check misses")
	void aLinkedShiftHoldsItsKind() throws Exception {
		// Nothing is planned at all. A check written from the planner's point of view sees an unused
		// kind here and deletes it, and the shift is left linked to a kind that does not exist —
		// counting toward nothing, which reads on the screen exactly like a shift nobody joined.
		//
		// Lower case again, because that is how the column stores what the temple typed.
		createShift("""
				{"title":"Peel vegetables","shiftDate":"%s","startTime":"15:00","endTime":"18:00",
				 "capacity":4,"mealDate":"%s","mealKind":"dinner"}
				""".formatted(DATE, DATE));

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

		// Added by mistake, used for nothing. This is the case the delete button is really for, and
		// refusing when in use must not cost it.
		mvc.perform(authed(delete("/api/v1/meal-kinds/{id}", id)))
				.andExpect(status().isNoContent());

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM meal_kinds WHERE id = ?", Integer.class, id)).isZero();
	}

	@Test
	@DisplayName("the other temple's use of the name does not hold this temple's kind")
	void useIsCountedPerTemple() throws Exception {
		// Mysuru has planned a lunch. Bengaluru has not, and its own Lunch is therefore unused —
		// the in-use check runs inside the request's tenant, so it must not see across.
		insertPlanAs(otherTenant, otherRecipe, otherUser, "Lunch");

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

	/** The same request unperformed, for the tests that expect it to be refused rather than to work. */
	private MockHttpServletRequestBuilder renameRequest(
			UUID id, String name, int sortOrder, String readyTime) {
		return authed(put("/api/v1/meal-kinds/{id}", id))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name":"%s","sortOrder":%d,"defaultReadyTime":"%s","isEvent":false,
						 "needsOccasion":false}
						""".formatted(name, sortOrder, readyTime));
	}

	private void plan(String kind) throws Exception {
		mvc.perform(authed(post("/api/v1/meal-plans")).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"planDate":"%s","mealKind":"%s","recipeId":"%s","targetYield":200,"adults":200,
								 "crewRequired":4}
								""".formatted(DATE, kind, khichdi)))
				.andExpect(status().isCreated());
	}

	private UUID planId() {
		return admin.queryForObject(
				"SELECT id FROM meal_plans WHERE tenant_id = ?", UUID.class, tenant);
	}

	/** The meal's own row, made the way the application makes it — on demand, through the service. */
	private UUID serviceFor(UUID forTenant, String date, String kind) {
		TenantContext.set(forTenant);
		try {
			return servedMealService.serviceFor(LocalDate.parse(date), kind, null);
		} finally {
			TenantContext.clear();
		}
	}

	private String createShift(String json) throws Exception {
		String body = mvc.perform(authed(post("/api/v1/shifts"))
						.contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(body).get("id").asText();
	}

	private void signUp(String shiftId, String volunteerUid) {
		admin.update("""
				INSERT INTO shift_signups (tenant_id, shift_id, volunteer_user_id)
				VALUES (?, ?::uuid, (SELECT id FROM users WHERE firebase_uid = ?))
				""", tenant, shiftId, volunteerUid);
	}

	private UUID kindId(UUID forTenant, String name) {
		return admin.queryForObject(
				"SELECT id FROM meal_kinds WHERE tenant_id = ? AND lower(name) = lower(?)",
				UUID.class, forTenant, name);
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

	private UUID insertPlanAs(UUID forTenant, UUID recipe, UUID user, String kind) {
		return admin.queryForObject("""
				INSERT INTO meal_plans (tenant_id, plan_date, meal_kind, ready_by, recipe_id,
						target_yield, day_type, status, created_by)
				VALUES (?, ?::date, ?, '12:00', ?, 200, 'REGULAR', 'PLANNED', ?)
				RETURNING id
				""", UUID.class, forTenant, DATE, kind, recipe, user);
	}

	private UUID insertServiceAs(UUID forTenant, String kind) {
		return admin.queryForObject("""
				INSERT INTO meal_services (tenant_id, plan_date, meal_kind)
				VALUES (?, ?::date, ?) RETURNING id
				""", UUID.class, forTenant, DATE, kind);
	}

	private UUID insertShiftAs(UUID forTenant, UUID user, String kind) {
		return admin.queryForObject("""
				INSERT INTO shifts (tenant_id, title, shift_date, start_time, end_time, capacity,
						created_by, meal_date, meal_kind)
				VALUES (?, 'Lunch prep', ?::date, '06:00', '10:00', 6, ?, ?::date, ?)
				RETURNING id
				""", UUID.class, forTenant, DATE, user, DATE, kind);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
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
