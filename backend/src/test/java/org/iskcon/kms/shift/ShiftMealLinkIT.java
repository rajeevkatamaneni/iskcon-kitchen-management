package org.iskcon.kms.shift;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A shift says which meal it is for (D-14), through the full stack.
 *
 * <p>The case that decided the ruling is {@link #aLunchPrepShiftStopsInflatingBreakfast()}, and it
 * is written here in the order the mistake actually happens: the shift is posted first with no link
 * at all, and asserted to land on <em>breakfast</em> — which is what the product does today and what
 * everybody agreed was wrong — and only then linked to the lunch it was always for. Both readings
 * are asserted, because the value of the change is the difference between them and a test that only
 * showed the second would not be evidence of anything.
 *
 * <p>The kitchen is deliberately thin: no staff at all, so every figure in here is volunteers and
 * nothing can be confused for a rostered cook. What the roster does with its own half is
 * {@code MealCrewIT}'s question, not this one's.
 */
@AutoConfigureMockMvc
@Import(ShiftMealLinkIT.StubVerifierConfiguration.class)
class ShiftMealLinkIT extends AbstractIntegrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** A Monday, and nothing turns on that. */
	private static final String DATE = "2026-09-07";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private MealKindService mealKindService;

	@MockBean
	private Scheduler scheduler;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID khichdi;

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
		insertUser("uid-admin", "TEMPLE_ADMIN", "+919876500001");
		insertUser("uid-vol-1", "VOLUNTEER", "+919876500091");
		insertUser("uid-vol-2", "VOLUNTEER", "+919876500092");

		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Rice') RETURNING id
				""", UUID.class, tenant);
		khichdi = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, 'Khichdi', ?, 100, 'KG') RETURNING id
				""", UUID.class, tenant, category);

		TenantContext.set(tenant);
		try {
			mealKindService.seedForCurrentTenant();
		} finally {
			TenantContext.clear();
		}
		signIn("uid-admin");
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
		admin.execute("DELETE FROM platform_audit_events");
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- The link itself --------------------------------------------------

	@Test
	@DisplayName("a shift posted for a meal saves and reads back with the meal it was posted for")
	void aLinkSavesAndReadsBack() throws Exception {
		String id = createId("""
				{"title":"Janmashtami lunch prep","shiftDate":"%s","startTime":"06:00","endTime":"10:00",
				 "capacity":6,"mealDate":"%s","mealKind":"Event","mealEventName":"Janmashtami Feast"}
				""".formatted(DATE, DATE));

		mvc.perform(authed(get("/api/v1/shifts/{id}", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mealDate").value(DATE))
				.andExpect(jsonPath("$.mealKind").value("Event"))
				// Stored as the temple typed it. The folding happens where the match is made, so the
				// planner gets its own capitals back rather than a lower-cased version of them.
				.andExpect(jsonPath("$.mealEventName").value("Janmashtami Feast"));

		// And on the list, not only on the one-shift read: the planner draws from the list.
		mvc.perform(authed(get("/api/v1/shifts")))
				.andExpect(jsonPath("$[0].mealKind").value("Event"));
	}

	@Test
	@DisplayName("an unlinked shift reads back as unlinked, on every one of the three fields")
	void anUnlinkedShiftSaysSoRatherThanSayingNothing() throws Exception {
		String id = createId("""
				{"title":"Saturday morning seva","shiftDate":"%s","startTime":"06:00","endTime":"10:00",
				 "capacity":6}
				""".formatted(DATE));

		// Null and present, not absent. A reader must be able to tell "this shift is not linked" from
		// "this endpoint does not say", and only one of those is true here.
		mvc.perform(authed(get("/api/v1/shifts/{id}", id)))
				.andExpect(jsonPath("$.mealDate").doesNotExist())
				.andExpect(jsonPath("$.mealKind").doesNotExist())
				.andExpect(jsonPath("$.mealEventName").doesNotExist());
	}

	@Test
	@DisplayName("half a link is refused by name and nothing is written")
	void halfALinkIsRefused() throws Exception {
		mvc.perform(create("""
				{"title":"Lunch prep","shiftDate":"%s","startTime":"06:00","endTime":"10:00","capacity":6,
				 "mealKind":"Lunch"}
				""".formatted(DATE)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400125"));

		// The other half, and an event name with neither: a name on its own names nothing.
		mvc.perform(create("""
				{"title":"Lunch prep","shiftDate":"%s","startTime":"06:00","endTime":"10:00","capacity":6,
				 "mealDate":"%s"}
				""".formatted(DATE, DATE)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400125"));
		mvc.perform(create("""
				{"title":"Lunch prep","shiftDate":"%s","startTime":"06:00","endTime":"10:00","capacity":6,
				 "mealEventName":"Janmashtami"}
				""".formatted(DATE)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400125"));

		// Refused, not half-saved. A shift with a date and no kind would count toward no meal at all
		// while reading on the planner as one somebody had deliberately committed.
		Integer written = admin.queryForObject("SELECT count(*) FROM shifts", Integer.class);
		assert written == 0 : "a refused link should write nothing, but " + written + " shifts exist";
	}

	@Test
	@DisplayName("the database refuses half a link too, whatever route the row comes in by")
	void theDatabaseIsTheBackstop() {
		// KMS-400125 is what a caller reads and it is raised before this statement would ever be
		// sent. This asserts the constraint underneath it, which is the thing that still holds when
		// the next writer of a row into `shifts` forgets the service — a migration, a fixture, a
		// support script. Written as raw SQL for exactly that reason: it is the route that bypasses
		// every check the application makes.
		String half = """
				INSERT INTO shifts (tenant_id, title, shift_date, start_time, end_time, capacity,
						created_by, meal_kind)
				VALUES (?, 'Lunch prep', ?::date, '06:00', '10:00', 6,
						(SELECT id FROM users WHERE firebase_uid = 'uid-admin'), 'Lunch')
				""";
		try {
			admin.update(half, tenant, DATE);
			throw new AssertionError("the shifts_meal_link_complete CHECK should have refused this row");
		} catch (org.springframework.dao.DataIntegrityViolationException expected) {
			assert expected.getMessage().contains("shifts_meal_link_complete")
					: "refused by the wrong constraint: " + expected.getMessage();
		}
	}

	@Test
	@DisplayName("an edit can take the link off again, and the shift goes back to counting by its hours")
	void anEditCanUnlinkAShift() throws Exception {
		plan("Breakfast", null);
		String id = createId("""
				{"title":"Lunch prep","shiftDate":"%s","startTime":"06:00","endTime":"10:00","capacity":6,
				 "mealDate":"%s","mealKind":"Lunch"}
				""".formatted(DATE, DATE));
		signUp(id, "uid-vol-1");

		// Linked to a lunch nobody has planned: it counts toward nothing, and breakfast is not
		// credited with it. A link to a meal that does not exist yet is the normal case, not an error
		// — the hands are found before the menu is decided.
		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$[0].mealKind").value("Breakfast"))
				.andExpect(jsonPath("$[0].volunteers").value(0));

		mvc.perform(authed(put("/api/v1/shifts/{id}", id)).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title":"Morning seva","shiftDate":"%s","startTime":"06:00","endTime":"10:00",
								 "capacity":6}
								""".formatted(DATE)))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/shifts/{id}", id)))
				.andExpect(jsonPath("$.mealDate").doesNotExist());
		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$[0].mealKind").value("Breakfast"))
				.andExpect(jsonPath("$[0].volunteers").value(1));
	}

	// ---- What the link changes about the count ----------------------------

	@Test
	@DisplayName("a lunch-prep shift stops inflating breakfast once it says it is for lunch")
	void aLunchPrepShiftStopsInflatingBreakfast() throws Exception {
		plan("Breakfast", 4);
		plan("Lunch", 8);

		// Posted 06:00–10:00 to cut vegetables for lunch, and posted the way the product allows it
		// today: with no link. One volunteer signs up.
		String id = createId("""
				{"title":"Cut vegetables for lunch","shiftDate":"%s","startTime":"06:00","endTime":"10:00",
				 "capacity":6}
				""".formatted(DATE));
		signUp(id, "uid-vol-1");

		// This is the defect, asserted rather than described. Breakfast is due at 07:30, which is
		// inside 06:00–10:00, so the clock hands breakfast a volunteer who will be chopping for a
		// lunch served at 12:00 — and hands lunch nobody at all.
		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].mealKind").value("Breakfast"))
				.andExpect(jsonPath("$[0].volunteers").value(1))
				.andExpect(jsonPath("$[1].mealKind").value("Lunch"))
				.andExpect(jsonPath("$[1].volunteers").value(0));

		// Now say what it is for. Nothing else about the shift moves — same day, same hours, same
		// volunteer.
		mvc.perform(authed(put("/api/v1/shifts/{id}", id)).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title":"Cut vegetables for lunch","shiftDate":"%s","startTime":"06:00",
								 "endTime":"10:00","capacity":6,"mealDate":"%s","mealKind":"Lunch"}
								""".formatted(DATE, DATE)))
				.andExpect(status().isNoContent());

		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				// Breakfast goes DOWN, deliberately. This is the number Rajeev has already seen move,
				// and it is the over-count being corrected: those hands were never coming to breakfast.
				.andExpect(jsonPath("$[0].mealKind").value("Breakfast"))
				.andExpect(jsonPath("$[0].volunteers").value(0))
				.andExpect(jsonPath("$[0].rostered").value(0))
				.andExpect(jsonPath("$[0].crewRequired").value(4))
				.andExpect(jsonPath("$[0].shortOfCrew").value(true))
				// And lunch gains what breakfast lost, which is the under-count fixed in the same move.
				.andExpect(jsonPath("$[1].mealKind").value("Lunch"))
				.andExpect(jsonPath("$[1].volunteers").value(1))
				.andExpect(jsonPath("$[1].rostered").value(1));
	}

	@Test
	@DisplayName("a shift left unlinked still counts toward every meal its hours span")
	void anUnlinkedShiftStillCountsByTheClock() throws Exception {
		plan("Breakfast", 4);
		plan("Lunch", 8);
		plan("Dinner", 4);

		// The festival all-dayer. The devotee really is there for all three meals, so all three get
		// them — and this is why the clock rule is kept rather than replaced.
		String id = createId("""
				{"title":"Festival, all day","shiftDate":"%s","startTime":"06:00","endTime":"22:00",
				 "capacity":20}
				""".formatted(DATE));
		signUp(id, "uid-vol-1");

		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$[0].mealKind").value("Breakfast"))
				.andExpect(jsonPath("$[0].volunteers").value(1))
				.andExpect(jsonPath("$[1].mealKind").value("Lunch"))
				.andExpect(jsonPath("$[1].volunteers").value(1))
				.andExpect(jsonPath("$[2].mealKind").value("Dinner"))
				.andExpect(jsonPath("$[2].volunteers").value(1));
	}

	@Test
	@DisplayName("a linked shift counts toward its meal even where its hours cover no meal at all")
	void aLinkedShiftIgnoresTheClockEntirely() throws Exception {
		plan("Lunch", 8);

		// 14:00–16:00: after lunch is served and long before dinner, so the clock places this shift
		// nowhere. It is for the lunch, and it says so.
		String id = createId("""
				{"title":"Lunch clean-up and prep","shiftDate":"%s","startTime":"14:00","endTime":"16:00",
				 "capacity":6,"mealDate":"%s","mealKind":"Lunch"}
				""".formatted(DATE, DATE));
		signUp(id, "uid-vol-1");

		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$[0].mealKind").value("Lunch"))
				.andExpect(jsonPath("$[0].volunteers").value(1));
	}

	@Test
	@DisplayName("a shift posted days ahead of the meal it is for still counts toward it")
	void aLinkReachesAcrossTheCalendarToo() throws Exception {
		plan("Lunch", 8);

		// "Grind the masala on Thursday for Sunday's feast." The shift falls three days before the
		// meal, so a range built from the meal's own date would never load it — which would read as
		// zero on precisely the shift somebody took the trouble to link.
		String id = createId("""
				{"title":"Grind masala","shiftDate":"2026-09-04","startTime":"09:00","endTime":"12:00",
				 "capacity":4,"mealDate":"%s","mealKind":"Lunch"}
				""".formatted(DATE));
		signUp(id, "uid-vol-1");

		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].mealKind").value("Lunch"))
				.andExpect(jsonPath("$[0].volunteers").value(1));
	}

	// ---- The normalisation trap -------------------------------------------

	@Test
	@DisplayName("an event link matches on a name differing only in case and surrounding space")
	void anEventNameIsFoldedTheSameWayTheMealKeyFoldsIt() throws Exception {
		planEvent("Janmashtami Feast", "18:00", 20);
		plan("Lunch", 8);

		// Typed by a different person on a different day, in a different case, with a stray space at
		// each end. If the folding here diverged by one character from the rule the meal's own key
		// uses, this shift would match nothing — and it would read as a shift nobody had signed up
		// for, which is the failure that is impossible to notice.
		String id = createId("""
				{"title":"Feast prep","shiftDate":"%s","startTime":"06:00","endTime":"10:00","capacity":10,
				 "mealDate":"%s","mealKind":"event","mealEventName":"  janmashtami feast  "}
				""".formatted(DATE, DATE));
		signUp(id, "uid-vol-1");
		signUp(id, "uid-vol-2");

		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$.length()").value(2))
				// Lunch is due at 12:00 and comes first. It gets none of them, even though 06:00–10:00
				// would once have put them on breakfast and nothing here is on breakfast at all.
				.andExpect(jsonPath("$[0].mealKind").value("Lunch"))
				.andExpect(jsonPath("$[0].volunteers").value(0))
				.andExpect(jsonPath("$[1].mealKind").value("Event"))
				.andExpect(jsonPath("$[1].volunteers").value(2))
				.andExpect(jsonPath("$[1].crewRequired").value(20))
				.andExpect(jsonPath("$[1].shortOfCrew").value(true));
	}

	@Test
	@DisplayName("two events on one day are two meals, and a link reaches only the one it names")
	void oneEventsHandsAreNotTheOthers() throws Exception {
		planEvent("Janmashtami Feast", "18:00", 20);
		planEvent("Bhajan Prasadam", "20:00", 6);

		String feast = createId("""
				{"title":"Feast prep","shiftDate":"%s","startTime":"06:00","endTime":"22:00","capacity":10,
				 "mealDate":"%s","mealKind":"Event","mealEventName":"Janmashtami Feast"}
				""".formatted(DATE, DATE));
		signUp(feast, "uid-vol-1");

		// The shift runs until 22:00 and so covers both events by the clock. It is for one of them.
		mvc.perform(authed(get("/api/v1/meal-crew").param("from", DATE).param("to", DATE)))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].mealKind").value("Event"))
				.andExpect(jsonPath("$[0].volunteers").value(1))
				.andExpect(jsonPath("$[1].mealKind").value("Event"))
				.andExpect(jsonPath("$[1].volunteers").value(0));
	}

	// ---- helpers ----------------------------------------------------------

	private void plan(String kind, Integer crew) throws Exception {
		mvc.perform(authed(post("/api/v1/meal-plans")).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"planDate":"%s","mealKind":"%s","recipeId":"%s","targetYield":200,"adults":200,
								 "crewRequired":%s}
								""".formatted(DATE, kind, khichdi, crew == null ? "null" : crew)))
				.andExpect(status().isCreated());
	}

	/** An in-house event: a name, a ready-by of its own, and nothing leaving the temple. */
	private void planEvent(String name, String readyBy, Integer crew) throws Exception {
		mvc.perform(authed(post("/api/v1/meal-plans")).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"planDate":"%s","mealKind":"Event","recipeId":"%s","targetYield":200,"adults":200,
								 "readyBy":"%s","eventName":"%s","crewRequired":%s}
								""".formatted(DATE, khichdi, readyBy, name, crew == null ? "null" : crew)))
				.andExpect(status().isCreated());
	}

	private void signUp(String shiftId, String volunteerUid) {
		admin.update("""
				INSERT INTO shift_signups (tenant_id, shift_id, volunteer_user_id)
				VALUES (?, ?::uuid, (SELECT id FROM users WHERE firebase_uid = ?))
				""", tenant, shiftId, volunteerUid);
	}

	private MockHttpServletRequestBuilder create(String json) {
		return authed(post("/api/v1/shifts")).contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private String createId(String json) throws Exception {
		String body = mvc.perform(create(json)).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JSON.readTree(body).get("id").asText();
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}

	private void insertUser(String uid, String role, String phone) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Person', ?, ?, ?, 'ACTIVE')
				""", tenant, uid, uid + "@example.com", phone, role);
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
