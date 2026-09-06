package org.iskcon.kms.meal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Reusing a plan (2026-09-05) — the screen that replaced "Duplicate last week".
 *
 * <p>What is worth proving here is what the change argued about. The old button could copy last week
 * onto this week and nothing else, which is week-shaped in an application for temples buying on
 * every cycle there is; the window is now any length. Copying has to be <em>surgical</em>: a festival
 * feast is never carried, because its occasion comes from the calendar on the day it is cooked and
 * the same dishes on an ordinary Wednesday are a large lunch wearing the wrong name. An event is
 * carried only when somebody asks for it by name, since nothing in the schema says whether one
 * repeats. And the whole point of the screen is that all of this is <em>shown before it is done</em>
 * — so the preview and the commit must be the same walk, or the preview is a second opinion.
 */
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(ReusePlanIT.Stubs.class)
class ReusePlanIT extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private MealKindService mealKindService;

	@Autowired
	private StubTokenVerifier stubVerifier;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID sago;
	private UUID rice;

	/** The Monday the source fortnight starts on, and the Monday it is copied to. */
	private static final LocalDate SOURCE = LocalDate.parse("2025-03-03");
	private static final LocalDate TARGET = LocalDate.parse("2025-03-17");

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());

		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('reuse-temple', 'Reuse Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-reuse', 'Planner', 'reuse@example.com', '+919876500099',
						'TEMPLE_ADMIN', 'ACTIVE')
				""", tenant);
		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Rice') RETURNING id
				""", UUID.class, tenant);
		sago = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, 'Sabudana Khichadi', ?, 100, 'KG') RETURNING id
				""", UUID.class, tenant, category);
		rice = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, 'Plain Rice', ?, 100, 'KG') RETURNING id
				""", UUID.class, tenant, category);

		// The fast reads ingredients, not recipe names, so the rice has to actually carry grain —
		// a recipe with no ingredients is compatible with every fast there is.
		UUID grain = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit,
						is_ekadashi_prohibited)
				VALUES (?, 'Rice', 'GRAIN', 'KG', true) RETURNING id
				""", UUID.class, tenant);
		admin.update("""
				INSERT INTO recipe_ingredients (tenant_id, recipe_id, ingredient_id, quantity, unit,
						line_order)
				VALUES (?, ?, ?, 10, 'KG', 1)
				""", tenant, rice, grain);

		TenantContext.set(tenant);
		try {
			mealKindService.seedForCurrentTenant();
		} finally {
			TenantContext.clear();
		}
		stubVerifier.accept("uid-reuse");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM meal_services");
		admin.execute("DELETE FROM meal_plans");
		admin.execute("DELETE FROM meal_kinds");
		admin.execute("DELETE FROM calendar_days");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a fortnight is one window, not two weeks — the shape the old button could not do")
	void aFortnightIsOneWindow() throws Exception {
		for (int i = 0; i < 14; i++) {
			plan(SOURCE.plusDays(i), "Lunch", rice);
		}

		mvc.perform(reuse("/reuse", 14))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.copied").value(14))
				.andExpect(jsonPath("$.daysLeftAlone").value(0));

		// Fourteen days landed on fourteen days. The seventh, which the week-shaped tool could never
		// have reached, is there.
		assertThat(plannedOn(TARGET.plusDays(13))).isEqualTo(1);
	}

	@Test
	@DisplayName("one day is not a special case: it is how last year's festival reaches this year")
	void oneDayIsNotASpecialCase() throws Exception {
		plan(SOURCE, "Lunch", rice);
		plan(SOURCE, "Dinner", rice);

		// The date moves with the Vaishnava calendar, so both ends are picked by hand — a year and
		// eleven days apart here, which no offset arithmetic would have found.
		mvc.perform(post("/api/v1/meal-plans/reuse")
						.contentType(MediaType.APPLICATION_JSON)
						.header("Authorization", "Bearer valid-token")
						.content("""
								{"sourceStart":"%s","days":1,"targetStart":"2026-03-14"}
								""".formatted(SOURCE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.copied").value(2));

		assertThat(plannedOn(LocalDate.parse("2026-03-14"))).isEqualTo(2);
	}

	@Test
	@DisplayName("a festival feast is never carried, and the preview says why rather than staying quiet")
	void aFeastBelongsToItsDate() throws Exception {
		plan(SOURCE, "Lunch", rice);
		plan(SOURCE, "Festival feast", rice);

		mvc.perform(reuse("/reuse/preview", 7))
				.andExpect(status().isOk())
				// Not among the kinds on offer…
				.andExpect(jsonPath("$.kinds[?(@.mealKind == 'Festival feast')]").isEmpty())
				// …and said out loud, because a thing silently missing is a thing nobody can query.
				.andExpect(jsonPath("$.excluded[0].label").value(org.hamcrest.Matchers.containsString("Festival feast")))
				.andExpect(jsonPath("$.excluded[0].reason")
						.value(org.hamcrest.Matchers.containsString("calendar")));

		mvc.perform(reuse("/reuse", 7)).andExpect(jsonPath("$.copied").value(1));
		assertThat(kindsOn(TARGET)).containsExactly("Lunch");
	}

	@Test
	@DisplayName("an event is carried only when it is asked for by name")
	void anEventIsOptedIn() throws Exception {
		plan(SOURCE, "Lunch", rice);
		planEvent(SOURCE, "Bhajan Prasadam");
		planEvent(SOURCE.plusDays(7), "Bhajan Prasadam");
		planEvent(SOURCE.plusDays(2), "Sharma wedding delivery");

		// The count is the honest signal for "does this repeat?" — nothing in the schema records it,
		// so the screen shows the evidence and a person decides.
		mvc.perform(reuse("/reuse/preview", 14))
				.andExpect(jsonPath("$.events[?(@.eventName == 'Bhajan Prasadam')].occurrences").value(2))
				.andExpect(jsonPath("$.events[?(@.eventName == 'Sharma wedding delivery')].occurrences").value(1));

		// Nothing asked for, nothing carried: an event was arranged, and arranging it again is a
		// decision rather than something inherited.
		mvc.perform(reuse("/reuse", 14)).andExpect(jsonPath("$.copied").value(1));
		assertThat(kindsOn(TARGET)).containsExactly("Lunch");

		admin.update("DELETE FROM meal_plans WHERE plan_date >= ?", TARGET);
		mvc.perform(post("/api/v1/meal-plans/reuse")
						.contentType(MediaType.APPLICATION_JSON)
						.header("Authorization", "Bearer valid-token")
						.content("""
								{"sourceStart":"%s","days":14,"targetStart":"%s",
								 "mealKinds":["Lunch"],"eventNames":["Bhajan Prasadam"]}
								""".formatted(SOURCE, TARGET)))
				.andExpect(jsonPath("$.copied").value(3));
	}

	@Test
	@DisplayName("a day that already has meals is left alone whole, and counted")
	void nothingIsEverOverwritten() throws Exception {
		plan(SOURCE, "Lunch", rice);
		plan(SOURCE.plusDays(1), "Lunch", rice);
		plan(TARGET, "Dinner", rice);

		mvc.perform(reuse("/reuse", 7))
				.andExpect(jsonPath("$.copied").value(1))
				.andExpect(jsonPath("$.daysLeftAlone").value(1));

		// The Dinner somebody had already planned is still the only thing on that day.
		assertThat(kindsOn(TARGET)).containsExactly("Dinner");
	}

	@Test
	@DisplayName("the preview names the meals a fast will refuse, before anything is written")
	void theFastIsShownBeforeItBites() throws Exception {
		plan(SOURCE, "Breakfast", sago);
		plan(SOURCE, "Lunch", rice);
		admin.update("""
				INSERT INTO calendar_days (tenant_id, cal_date, tithi, paksa, masa, is_ekadashi,
						ekadashi_name, fast_type)
				VALUES (?, ?, 11, 1, 11, true, 'Papamocani Ekadasi', 'Ekadashi')
				""", tenant, TARGET);

		mvc.perform(reuse("/reuse/preview", 1))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.days[0].fastName").value("Papamocani Ekadasi"))
				// Rice carries grain and is refused; sago does not and survives. Both are named on the
				// screen before the button is pressed, which is the whole reason the screen exists.
				.andExpect(jsonPath("$.days[0].meals[?(@.recipeName == 'Plain Rice')].copied").value(false))
				.andExpect(jsonPath("$.days[0].meals[?(@.recipeName == 'Sabudana Khichadi')].copied").value(true))
				.andExpect(jsonPath("$.totals.notCopied").value(1))
				.andExpect(jsonPath("$.totals.meals").value(1));

		// And the commit does exactly what the preview said — the same walk, not a second opinion.
		mvc.perform(reuse("/reuse", 1))
				.andExpect(jsonPath("$.copied").value(1))
				.andExpect(jsonPath("$.notCopied").value(1));
		assertThat(kindsOn(TARGET)).containsExactly("Breakfast");
	}

	@Test
	@DisplayName("an empty source says so rather than offering an empty list of things to tick")
	void anEmptySourceSaysSo() throws Exception {
		mvc.perform(reuse("/reuse/preview", 7))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sourceWasEmpty").value(true))
				.andExpect(jsonPath("$.kinds").isEmpty());
	}

	@Test
	@DisplayName("a window nobody could mean is refused rather than walked across a year of plan")
	void theWindowIsCapped() throws Exception {
		mvc.perform(post("/api/v1/meal-plans/reuse/preview")
						.contentType(MediaType.APPLICATION_JSON)
						.header("Authorization", "Bearer valid-token")
						.content("""
								{"sourceStart":"%s","days":400,"targetStart":"%s"}
								""".formatted(SOURCE, TARGET)))
				.andExpect(status().isBadRequest());
	}

	// ---- helpers ----------------------------------------------------------

	private MockHttpServletRequestBuilder reuse(String path, int days) {
		return post("/api/v1/meal-plans" + path)
				.contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", "Bearer valid-token")
				.content("""
						{"sourceStart":"%s","days":%d,"targetStart":"%s"}
						""".formatted(SOURCE, days, TARGET));
	}

	private void plan(LocalDate date, String kind, UUID recipe) {
		admin.update("""
				INSERT INTO meal_plans (tenant_id, plan_date, meal_kind, ready_by, recipe_id,
						target_yield, day_type, status, adults, children, seniors, created_by)
				VALUES (?, ?, ?, TIME '12:00', ?, ?, 'REGULAR', 'PLANNED', 100, 0, 0,
						(SELECT id FROM users WHERE firebase_uid = 'uid-reuse'))
				""", tenant, date, kind, recipe, BigDecimal.valueOf(100));
	}

	private void planEvent(LocalDate date, String eventName) {
		admin.update("""
				INSERT INTO meal_plans (tenant_id, plan_date, meal_kind, ready_by, recipe_id,
						target_yield, day_type, status, event_name, is_outside, adults, children,
						seniors, created_by)
				VALUES (?, ?, 'Event', TIME '18:00', ?, 30, 'REGULAR', 'PLANNED', ?, false, 30, 0, 0,
						(SELECT id FROM users WHERE firebase_uid = 'uid-reuse'))
				""", tenant, date, rice, eventName);
	}

	private int plannedOn(LocalDate date) {
		return admin.queryForObject(
				"SELECT count(*) FROM meal_plans WHERE tenant_id = ? AND plan_date = ? AND status <> 'CANCELLED'",
				Integer.class, tenant, date);
	}

	private java.util.List<String> kindsOn(LocalDate date) {
		return admin.queryForList("""
				SELECT DISTINCT meal_kind FROM meal_plans
				WHERE tenant_id = ? AND plan_date = ? AND status <> 'CANCELLED' ORDER BY meal_kind
				""", String.class, tenant, date);
	}

	/** The one token these tests present, standing for the planner who signed in. */
	static class StubTokenVerifier implements org.iskcon.kms.auth.TokenVerifier {

		private String uid;

		void accept(String subject) {
			this.uid = subject;
		}

		@Override
		public VerifiedSubject verify(String idToken) throws InvalidTokenException {
			if (uid == null || !"valid-token".equals(idToken)) {
				throw new InvalidTokenException("Unrecognised token");
			}
			return new VerifiedSubject(uid, uid + "@example.com", "+919876500099");
		}
	}

	@org.springframework.boot.test.context.TestConfiguration
	static class Stubs {

		@org.springframework.context.annotation.Bean
		@org.springframework.context.annotation.Primary
		StubTokenVerifier stubTokenVerifier() {
			return new StubTokenVerifier();
		}
	}
}
