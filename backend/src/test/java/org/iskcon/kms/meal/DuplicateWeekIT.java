package org.iskcon.kms.meal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.calendar.CalendarService;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Copying last week into this one (E3).
 *
 * <p>What is worth testing is not that it copies — it is everything it declines to do. A planner
 * presses this twice, or presses it on a week they have half filled in already, and the shortcut
 * must not cost them the work they did by hand.
 */
class DuplicateWeekIT extends AbstractIntegrationTest {

	// A Monday, so a week is a tidy seven days from here.
	private static final LocalDate LAST_WEEK = LocalDate.of(2025, 1, 6);
	private static final LocalDate THIS_WEEK = LocalDate.of(2025, 1, 13);

	@Autowired
	private MealPlanService mealPlanService;

	@Autowired
	private MealKindService mealKindService;

	@Autowired
	private CalendarService calendarService;

	@Autowired
	private UserRepository users;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID khichdi;
	private AuthenticatedUser actor;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		clean();
		tenant = admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('radha-govinda', 'Bengaluru Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-staff', 'Staff One', 'staff@example.com', '+919812345678', 'KITCHEN_STAFF', 'ACTIVE')
				""", tenant);
		UUID category = admin.queryForObject(
				"INSERT INTO recipe_categories (tenant_id, name) VALUES (?, 'Rice') RETURNING id",
				UUID.class, tenant);
		khichdi = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				VALUES (?, 'Khichdi', ?, 100, 'SERVINGS') RETURNING id
				""", UUID.class, tenant, category);

		TenantContext.set(tenant);
		// Read the person back rather than constructing one: AuthenticatedUser is built from the
		// real row, which is where role and tenant come from in every request too.
		actor = new AuthenticatedUser(users.findByFirebaseUid("uid-staff").orElseThrow());
		mealKindService.seedForCurrentTenant();
		calendarService.precomputeForCurrentTenant(LocalDate.of(2025, 1, 1), 60);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		clean();
	}

	/**
	 * Ordered by the foreign keys: creating a meal writes an audit row that references the person who
	 * created it, so the log goes before the people. Run before each test as well as after, so one
	 * failure does not leave rows that fail every test behind it for a different reason.
	 */
	private void clean() {
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM meal_plans");
		admin.execute("DELETE FROM calendar_days");
		admin.execute("DELETE FROM calendar_precompute_state");
		admin.execute("DELETE FROM recipe_ingredients");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM meal_kinds");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("last week's meals land on the matching days of this one")
	void copiesOntoTheMatchingWeekday() {
		plan(LAST_WEEK, "Lunch", 200);            // Monday
		plan(LAST_WEEK.plusDays(3), "Lunch", 150); // Thursday

		DuplicateWeekResult result = mealPlanService.duplicateWeek(actor, THIS_WEEK);

		assertThat(result.copied()).isEqualTo(2);
		assertThat(result.sourceWasEmpty()).isFalse();
		assertThat(mealsOn(THIS_WEEK)).hasSize(1);
		assertThat(mealsOn(THIS_WEEK.plusDays(3))).hasSize(1);
		// Monday's meal is on Monday, not shuffled onto the first free day.
		assertThat(mealsOn(THIS_WEEK).get(0).targetServings().intValue()).isEqualTo(200);
		assertThat(mealsOn(THIS_WEEK.plusDays(3)).get(0).targetServings().intValue()).isEqualTo(150);
	}

	@Test
	@DisplayName("pressing it twice changes nothing the second time")
	void isSafeToPressTwice() {
		plan(LAST_WEEK, "Lunch", 200);

		DuplicateWeekResult first = mealPlanService.duplicateWeek(actor, THIS_WEEK);
		DuplicateWeekResult second = mealPlanService.duplicateWeek(actor, THIS_WEEK);

		assertThat(first.copied()).isEqualTo(1);
		assertThat(second.copied()).isZero();
		assertThat(second.daysAlreadyPlanned()).isEqualTo(1);
		// The day has one meal, not two.
		assertThat(mealsOn(THIS_WEEK)).hasSize(1);
	}

	@Test
	@DisplayName("a day the planner had already filled in is left exactly as they left it")
	void neverOverwritesWorkDoneByHand() {
		plan(LAST_WEEK, "Lunch", 200);
		plan(LAST_WEEK.plusDays(1), "Lunch", 300);
		// The planner has already done Tuesday themselves, differently.
		plan(THIS_WEEK.plusDays(1), "Dinner", 999);

		DuplicateWeekResult result = mealPlanService.duplicateWeek(actor, THIS_WEEK);

		assertThat(result.copied()).isEqualTo(1);
		assertThat(result.daysAlreadyPlanned()).isEqualTo(1);
		List<MealPlanView> tuesday = mealsOn(THIS_WEEK.plusDays(1));
		assertThat(tuesday).hasSize(1);
		assertThat(tuesday.get(0).targetServings().intValue()).isEqualTo(999);
		assertThat(tuesday.get(0).mealKind()).isEqualTo("Dinner");
	}

	@Test
	@DisplayName("an empty week to copy from says so rather than reporting success")
	void anEmptySourceIsSaidPlainly() {
		DuplicateWeekResult result = mealPlanService.duplicateWeek(actor, THIS_WEEK);

		assertThat(result.sourceWasEmpty()).isTrue();
		assertThat(result.copied()).isZero();
	}

	@Test
	@DisplayName("a cancelled meal is not resurrected by copying the week it was cancelled in")
	void cancelledMealsAreNotCopied() {
		UUID id = plan(LAST_WEEK, "Lunch", 200);
		mealPlanService.cancel(actor, id);

		DuplicateWeekResult result = mealPlanService.duplicateWeek(actor, THIS_WEEK);

		assertThat(result.sourceWasEmpty()).isTrue();
		assertThat(mealsOn(THIS_WEEK)).isEmpty();
	}

	// ---- helpers ----------------------------------------------------------

	private UUID plan(LocalDate date, String kind, int servings) {
		return mealPlanService.create(actor, new CreateMealPlanRequest(
				date, kind, khichdi, java.math.BigDecimal.valueOf(servings), null,
				null, null, null, null, null, null, null, null, null, null, false));
	}

	private List<MealPlanView> mealsOn(LocalDate date) {
		return mealPlanService.list(date, date, null, null).stream()
				.filter(m -> m.status() != MealStatus.CANCELLED)
				.toList();
	}
}
