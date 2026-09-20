package org.iskcon.kms.kitchen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.iskcon.kms.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * V150 runs against temples that already have meals, dishes and staff in them (Epic 12, T-350).
 *
 * <p><b>Why this exists.</b> V150 gives every existing meal a kitchen, every dish a kitchen on its meal
 * and every staff member a kitchen, one temple at a time. The migration role is not a superuser, so a
 * loop that forgot to adopt each temple would match nothing, report success, and then fail on the
 * NOT NULL at the end — or, worse, a loop that adopted the wrong temple would put one temple's meals
 * in another's kitchen. Neither shows on the empty database every other test migrates. So there are
 * five temples, each in a different shape the rules have to decide between:
 *
 * <ul>
 *   <li><b>A</b> — a main kitchen that plans meals, beside a store-only kitchen, another planner and an
 *       archived one. Everything goes to the main kitchen, and nothing is created.
 *   <li><b>B</b> — a main kitchen that only draws from the store, and a kitchen already called "main
 *       KITCHEN". Nothing plans meals, so a kitchen is created, and it cannot be called "Main kitchen"
 *       (names compare without case) nor be main (B has one).
 *   <li><b>C</b> — no kitchens at all. It gets "Main kitchen", marked main.
 *   <li><b>D</b> — its main kitchen is archived. The first open planner kitchen by name wins, not the
 *       archived main one and not the one merely created first.
 *   <li><b>E</b> — nothing but the temple row. Skipped: nobody to credit a kitchen to, nothing needing it.
 * </ul>
 *
 * <p>Temples A and B carry different People-needed and card-version figures, so a meal section copied
 * from the wrong temple's meal is a wrong number rather than a coincidence.
 *
 * <p>Following {@code MealRebuildMigrationIT}: a throwaway database, migrated as the unprivileged
 * migration role to the version before, seeded through the superuser (what is under test is the
 * migration, not the seed), migrated to V150 (see {@code AFTER} for why it stops there rather than
 * running to the end), and then exercised as the application role — a superuser bypasses row-level
 * security and a test run as one proves nothing about it.
 *
 * <p>The last group runs {@link KitchenOrder}'s SQL against the migrated schema as the application role,
 * with a temple adopted the way the application adopts one, because its rules (whose kitchen, who may
 * plan) are only as good as the queries that feed them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WhichKitchenMigrationIT extends AbstractIntegrationTest {

	private static final String BEFORE = "149";

	/**
	 * Where this database stops, and why it is not the end of the list (T-361).
	 *
	 * <p>What is under test is V150's backfill, and V150 deliberately leaves {@code meals.crew_required}
	 * and the two card columns where they are so that the figures it copied can be compared against the
	 * meals they came from. V151 and V152 — the tasks that moved People needed and the job-card version
	 * onto the section for good — then drop those three columns. Migrating to the end would therefore
	 * take away the only evidence that the copy was right, and two of the assertions here would be
	 * asserting against columns that no longer exist. So this database stops at V150, which is the
	 * version whose behaviour these tests describe. The drops themselves are proven by T-354's and
	 * T-356's own migration tests, and every other test class in the suite migrates to the end.
	 */
	private static final String AFTER = "150";

	private static final String DATABASE = "kms_which_kitchen_check";

	private String a;
	private String b;
	private String c;
	private String d;
	private String e;

	@BeforeAll
	void migrateOverTemplesWithMealsAndStaff() throws SQLException {
		recreateDatabase();
		migrate(BEFORE);
		seed();
		migrate(AFTER);
	}

	@AfterAll
	void dropDatabase() throws SQLException {
		try (Connection connection = adminConnection(); Statement s = connection.createStatement()) {
			s.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
		}
	}

	// ---- The backfill -----------------------------------------------------------------------------

	@Test
	@DisplayName("a temple whose main kitchen plans meals: every meal, dish and staff member goes there, and nothing is created")
	void mainKitchenThatPlansMeals() throws SQLException {
		String main = kitchenId(a, "Main Kitchen");
		assertThat(countOf("SELECT count(*) FROM kitchens WHERE tenant_id = '" + a + "'"))
				.as("A's four kitchens, and no fifth").isEqualTo(4);

		assertThat(strings("""
				SELECT DISTINCT mk.kitchen_id::text FROM meal_kitchens mk WHERE mk.tenant_id = '%s'
				""".formatted(a))).containsExactly(main);
		assertThat(countOf("SELECT count(*) FROM meal_kitchens WHERE tenant_id = '" + a + "'"))
				.as("one section per meal").isEqualTo(3);
		assertThat(strings("""
				SELECT DISTINCT kitchen_id::text FROM meal_dishes WHERE tenant_id = '%s'
				""".formatted(a))).containsExactly(main);
		assertThat(countOf("SELECT count(*) FROM meal_dishes WHERE tenant_id = '" + a + "'")).isEqualTo(6);

		// The two employed before V150; the people the KitchenOrder tests add afterwards are not its work.
		assertThat(strings("""
				SELECT full_name || '|' || kitchen_id || '|' || kitchen_needs_check
				FROM staff_profiles WHERE tenant_id = '%s' AND full_name IN ('Admin wk-a', 'Cook wk-a')
				ORDER BY full_name
				""".formatted(a))).containsExactly(
						"Admin wk-a|" + main + "|true",
						"Cook wk-a|" + main + "|true");
	}

	@Test
	@DisplayName("each section carries its own meal's People needed and job-card version, per temple")
	void sectionsCarryCrewAndCard() throws SQLException {
		// Joined back to the meal the section is for, so a section copied from any other meal fails.
		assertThat(countOf("""
				SELECT count(*) FROM meal_kitchens mk JOIN meals m ON m.id = mk.meal_id
				WHERE mk.tenant_id <> m.tenant_id
				   OR mk.crew_required IS DISTINCT FROM m.crew_required
				   OR mk.card_version <> m.card_version
				   OR mk.card_fingerprint IS DISTINCT FROM m.card_fingerprint
				""")).as("sections that disagree with their meal").isZero();

		assertThat(strings("""
				SELECT DISTINCT crew_required || '|' || card_version FROM meal_kitchens WHERE tenant_id = '%s'
				""".formatted(a))).containsExactly("5|2");
		assertThat(strings("""
				SELECT DISTINCT crew_required || '|' || card_version FROM meal_kitchens WHERE tenant_id = '%s'
				""".formatted(b))).containsExactly("3|1");
		assertThat(strings("""
				SELECT card_fingerprint FROM meal_kitchens WHERE tenant_id = '%s' ORDER BY card_fingerprint
				""".formatted(a))).containsExactly("fp-wk-a-1", "fp-wk-a-2", "fp-wk-a-3");

		assertThat(countOf("SELECT count(*) FROM meals"))
				.as("every meal in every temple has a section")
				.isEqualTo(countOf("SELECT count(DISTINCT meal_id) FROM meal_kitchens"));
	}

	@Test
	@DisplayName("a temple where nothing plans meals gets a new planner kitchen, not main, named around the one it has")
	void createdBesideAStoreOnlyMain() throws SQLException {
		assertThat(strings("""
				SELECT name || '|' || is_main || '|' || uses_meal_planner || '|' || status
				FROM kitchens WHERE tenant_id = '%s' ORDER BY name COLLATE "C"
				""".formatted(b))).containsExactly(
						"Deity Kitchen|true|false|ACTIVE",
						"Main kitchen (meals)|false|true|ACTIVE",
						"main KITCHEN|false|false|ACTIVE");

		String made = kitchenId(b, "Main kitchen (meals)");
		assertThat(strings("SELECT DISTINCT kitchen_id::text FROM meal_kitchens WHERE tenant_id = '" + b + "'"))
				.containsExactly(made);
		assertThat(strings("SELECT DISTINCT kitchen_id::text FROM meal_dishes WHERE tenant_id = '" + b + "'"))
				.containsExactly(made);
		assertThat(strings("SELECT DISTINCT kitchen_id::text FROM staff_profiles WHERE tenant_id = '" + b + "'"))
				.containsExactly(made);

		// The cook joined first; the kitchen is credited to the Temple Admin all the same.
		assertThat(strings("""
				SELECT u.full_name FROM kitchens k JOIN users u ON u.id = k.created_by WHERE k.id = '%s'
				""".formatted(made))).containsExactly("Admin wk-b");
	}

	@Test
	@DisplayName("a temple with no kitchens gets 'Main kitchen', marked main and using the planner")
	void createdAsMainWhereThereWereNone() throws SQLException {
		assertThat(strings("""
				SELECT name || '|' || is_main || '|' || uses_meal_planner FROM kitchens WHERE tenant_id = '%s'
				""".formatted(c))).containsExactly("Main kitchen|true|true");
		String made = kitchenId(c, "Main kitchen");
		assertThat(strings("SELECT DISTINCT kitchen_id::text FROM meal_kitchens WHERE tenant_id = '" + c + "'"))
				.containsExactly(made);
		assertThat(strings("""
				SELECT DISTINCT kitchen_id || '|' || kitchen_needs_check FROM staff_profiles WHERE tenant_id = '%s'
				""".formatted(c))).containsExactly(made + "|true");
	}

	@Test
	@DisplayName("an archived main kitchen is passed over for the first open planner kitchen by name")
	void archivedMainIsPassedOver() throws SQLException {
		String alpha = kitchenId(d, "alpha kitchen");
		assertThat(strings("SELECT DISTINCT kitchen_id::text FROM meal_kitchens WHERE tenant_id = '" + d + "'"))
				.containsExactly(alpha);
		assertThat(strings("SELECT DISTINCT kitchen_id::text FROM staff_profiles WHERE tenant_id = '" + d + "'"))
				.containsExactly(alpha);
		assertThat(countOf("SELECT count(*) FROM kitchens WHERE tenant_id = '" + d + "'"))
				.as("nothing created").isEqualTo(3);
	}

	@Test
	@DisplayName("a temple with no users and nothing to fill in is left without a kitchen")
	void anEmptyTempleIsSkipped() throws SQLException {
		assertThat(countOf("SELECT count(*) FROM kitchens WHERE tenant_id = '" + e + "'")).isZero();
	}

	@Test
	@DisplayName("the two kitchen columns are NOT NULL after the backfill, and the old meal columns are still there")
	void theTripwireStands() throws SQLException {
		assertThat(strings("""
				SELECT table_name || '.' || column_name || '=' || is_nullable FROM information_schema.columns
				WHERE table_schema = 'public' AND column_name = 'kitchen_id'
				  AND table_name IN ('meal_dishes', 'staff_profiles', 'meal_kitchens')
				ORDER BY table_name
				""")).containsExactly(
						"meal_dishes.kitchen_id=NO", "meal_kitchens.kitchen_id=NO", "staff_profiles.kitchen_id=NO");
		// V151 and V152 drop these; V150 must not, because the assertion above compares each section
		// with the meal it was copied from. This database stops at V150 (see AFTER).
		assertThat(countOf("""
				SELECT count(*) FROM information_schema.columns WHERE table_name = 'meals'
				  AND column_name IN ('crew_required', 'card_version', 'card_fingerprint')
				""")).isEqualTo(3);
	}

	// ---- The rules, as the application role -------------------------------------------------------

	@Test
	@DisplayName("a temple reads only its own meal kitchens, cannot write another's, and no temple reads none")
	void mealKitchensAreIsolated() throws SQLException {
		try (Connection app = appConnection()) {
			app.setAutoCommit(false);

			adopt(app, a);
			assertThat(count(app, "SELECT count(*) FROM meal_kitchens")).as("A reads its own").isEqualTo(3);
			assertThat(count(app, "SELECT count(*) FROM meal_kitchens WHERE tenant_id <> '" + a + "'")).isZero();
			app.rollback();

			adopt(app, c);
			assertThat(count(app, "SELECT count(*) FROM meal_kitchens")).as("C reads its own").isEqualTo(1);
			String aMeal = firstMealOf(a);
			String aKitchen = kitchenId(a, "Health Kitchen");
			assertThatThrownBy(() -> {
				try (PreparedStatement ps = app.prepareStatement("""
						INSERT INTO meal_kitchens (tenant_id, meal_id, kitchen_id) VALUES (?::uuid, ?::uuid, ?::uuid)
						""")) {
					ps.setString(1, a);
					ps.setString(2, aMeal);
					ps.setString(3, aKitchen);
					ps.executeUpdate();
				}
			}).isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
			app.rollback();

			assertThat(count(app, "SELECT count(*) FROM meal_kitchens")).as("no temple adopted").isZero();
			app.rollback();
		}
	}

	@Test
	@DisplayName("a dish cannot be put under a kitchen that is not on its meal, and can once the kitchen is added")
	void aDishIsUnderOneOfItsMealsKitchens() throws SQLException {
		String meal = firstMealOf(a);
		String health = kitchenId(a, "Health Kitchen");
		try (Connection app = appConnection()) {
			app.setAutoCommit(false);
			adopt(app, a);

			assertThatThrownBy(() -> insertDish(app, meal, health))
					.as("Health is one of A's kitchens, but it is not on this meal")
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("meal_dishes_kitchen_on_meal");
			app.rollback();

			adopt(app, a);
			try (PreparedStatement ps = app.prepareStatement("""
					INSERT INTO meal_kitchens (tenant_id, meal_id, kitchen_id) VALUES (?::uuid, ?::uuid, ?::uuid)
					""")) {
				ps.setString(1, a);
				ps.setString(2, meal);
				ps.setString(3, health);
				ps.executeUpdate();
			}
			insertDish(app, meal, health);
			assertThat(count(app, "SELECT count(*) FROM meal_dishes WHERE meal_id = '" + meal + "'")).isEqualTo(3);
			app.rollback();
		}
	}

	// ---- KitchenOrder's queries, as the application role ------------------------------------------

	@Test
	@DisplayName("the Temple Admin with no staff record may plan; kitchen staff with none may not")
	void mayPlanWithoutAStaffRecord() throws SQLException {
		withKitchenOrder(a, order -> {
			assertThat(order.kitchenOf(userId("uid-wk-a-admin2"))).isEmpty();
			assertThat(order.mayPlan(userId("uid-wk-a-admin2"), true)).isTrue();

			assertThat(order.kitchenOf(userId("uid-wk-a-nostaff"))).isEmpty();
			assertThat(order.mayPlan(userId("uid-wk-a-nostaff"), false)).isFalse();
		});
	}

	@Test
	@DisplayName("kitchen staff may plan only from a kitchen that uses the planner")
	void mayPlanFromAPlannerKitchenOnly() throws SQLException {
		withKitchenOrder(a, order -> {
			assertThat(order.kitchenOf(userId("uid-wk-a-deity"))).contains(UUID.fromString(kitchenId(a, "Deity Kitchen")));
			assertThat(order.mayPlan(userId("uid-wk-a-deity"), false)).as("Deity only draws from the store").isFalse();

			assertThat(order.mayPlan(userId("uid-wk-a-health"), false)).isTrue();
		});
	}

	@Test
	@DisplayName("a former employee has no kitchen, and another temple's user is not found at all")
	void onlyCurrentStaffOfThisTempleHaveAKitchen() throws SQLException {
		withKitchenOrder(a, order -> {
			assertThat(order.kitchenOf(userId("uid-wk-a-former"))).isEmpty();
			assertThat(order.mayPlan(userId("uid-wk-a-former"), false)).isFalse();
			assertThat(order.kitchenOf(userId("uid-wk-b-cook"))).as("B's cook, asked from A").isEmpty();
		});
	}

	@Test
	@DisplayName("Settings order is main, then open kitchens by name, then archived; the default follows it")
	void settingsOrderAndDefault() throws SQLException {
		withKitchenOrder(a, order -> {
			assertThat(order.settingsOrder()).extracting(KitchenOrder.KitchenRef::name)
					.containsExactly("Main Kitchen", "Deity Kitchen", "Health Kitchen", "Aardvark Kitchen");
			assertThat(order.defaultPlanningKitchen(userId("uid-wk-a-health")))
					.contains(UUID.fromString(kitchenId(a, "Health Kitchen")));
			assertThat(order.defaultPlanningKitchen(userId("uid-wk-a-deity")))
					.as("Deity does not plan meals, so the main kitchen")
					.contains(UUID.fromString(kitchenId(a, "Main Kitchen")));
			assertThat(order.defaultPlanningKitchen(null)).contains(UUID.fromString(kitchenId(a, "Main Kitchen")));
		});
		withKitchenOrder(d, order -> assertThat(order.defaultPlanningKitchen(null))
				.as("D's main kitchen is archived")
				.contains(UUID.fromString(kitchenId(d, "alpha kitchen"))));
	}

	// ---- The fixture ------------------------------------------------------------------------------

	/**
	 * Five temples in the V149 schema's shapes, then the people KitchenOrder is asked about, written
	 * after the migration because a staff record now has to name a kitchen.
	 */
	private void seed() throws SQLException {
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE FUNCTION pg_temp.seed_temple(p_slug text, p_n int, p_meals int, p_crew int, p_card int)
					RETURNS uuid LANGUAGE plpgsql AS $$
					DECLARE
						v_tenant uuid; v_admin uuid; v_cook uuid; v_cat uuid; v_recipe uuid; v_kind uuid;
						v_day uuid; v_meal uuid; i int;
					BEGIN
						INSERT INTO tenants (slug, name, latitude, longitude, timezone)
						VALUES (p_slug, 'Temple ' || p_slug, 12.97, 77.59, 'Asia/Kolkata') RETURNING id INTO v_tenant;
						-- The cook joined first, so "earliest user" and "earliest Temple Admin" differ.
						INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status, created_at)
						VALUES (v_tenant, 'uid-' || p_slug || '-cook', 'Cook ' || p_slug, p_slug || '-cook@example.com',
								'+91987650' || lpad((p_n * 10 + 1)::text, 4, '0'), 'KITCHEN_STAFF', 'ACTIVE',
								now() - interval '2 days')
						RETURNING id INTO v_cook;
						INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status, created_at)
						VALUES (v_tenant, 'uid-' || p_slug || '-admin', 'Admin ' || p_slug, p_slug || '-admin@example.com',
								'+91987650' || lpad((p_n * 10 + 2)::text, 4, '0'), 'TEMPLE_ADMIN', 'ACTIVE',
								now() - interval '1 day')
						RETURNING id INTO v_admin;
						INSERT INTO staff_profiles (tenant_id, user_id, full_name, job_title, employment_type, date_of_joining)
						VALUES (v_tenant, v_cook, 'Cook ' || p_slug, 'COOK', 'FULL_TIME', DATE '2026-01-01'),
							   (v_tenant, v_admin, 'Admin ' || p_slug, 'TEMPLE_ADMINISTRATOR', 'FULL_TIME', DATE '2026-01-01');

						INSERT INTO recipe_categories (tenant_id, name) VALUES (v_tenant, 'Rice') RETURNING id INTO v_cat;
						INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
						VALUES (v_tenant, 'Khichdi', v_cat, 100, 'KG') RETURNING id INTO v_recipe;
						INSERT INTO meal_kinds (tenant_id, name, sort_order, default_ready_time)
						VALUES (v_tenant, 'Lunch', 20, TIME '12:00') RETURNING id INTO v_kind;

						FOR i IN 1..p_meals LOOP
							INSERT INTO meal_plan_days (tenant_id, plan_date, day_type)
							VALUES (v_tenant, DATE '2026-01-01' + i, 'REGULAR') RETURNING id INTO v_day;
							INSERT INTO meals (tenant_id, meal_plan_day_id, meal_kind_id, ready_by, crew_required,
									card_version, card_fingerprint)
							VALUES (v_tenant, v_day, v_kind, TIME '12:00', p_crew, p_card, 'fp-' || p_slug || '-' || i)
							RETURNING id INTO v_meal;
							INSERT INTO meal_dishes (tenant_id, meal_id, recipe_id, target_yield, status, created_by)
							VALUES (v_tenant, v_meal, v_recipe, 100, 'PLANNED', v_admin),
								   (v_tenant, v_meal, v_recipe, 50, 'PLANNED', v_admin);
						END LOOP;
						RETURN v_tenant;
					END $$
					""");
			statement.execute("""
					CREATE FUNCTION pg_temp.kitchen(p_tenant uuid, p_name text, p_main boolean, p_planner boolean,
							p_status text) RETURNS void LANGUAGE sql AS $$
						INSERT INTO kitchens (tenant_id, name, is_main, uses_meal_planner, status, created_by)
						SELECT p_tenant, p_name, p_main, p_planner, p_status,
							   (SELECT id FROM users WHERE tenant_id = p_tenant ORDER BY created_at LIMIT 1)
					$$
					""");

			a = oneString(connection, "SELECT pg_temp.seed_temple('wk-a', 1, 3, 5, 2)::text");
			b = oneString(connection, "SELECT pg_temp.seed_temple('wk-b', 2, 2, 3, 1)::text");
			c = oneString(connection, "SELECT pg_temp.seed_temple('wk-c', 3, 1, NULL, 0)::text");
			d = oneString(connection, "SELECT pg_temp.seed_temple('wk-d', 4, 1, NULL, 0)::text");
			e = oneString(connection, """
					INSERT INTO tenants (slug, name, latitude, longitude, timezone)
					VALUES ('wk-e', 'Temple wk-e', 12.97, 77.59, 'Asia/Kolkata') RETURNING id::text
					""");

			// A: the main kitchen plans meals; the rest are there to be passed over.
			statement.execute("SELECT pg_temp.kitchen('" + a + "', 'Main Kitchen', true, true, 'ACTIVE')");
			statement.execute("SELECT pg_temp.kitchen('" + a + "', 'Deity Kitchen', false, false, 'ACTIVE')");
			statement.execute("SELECT pg_temp.kitchen('" + a + "', 'Health Kitchen', false, true, 'ACTIVE')");
			statement.execute("SELECT pg_temp.kitchen('" + a + "', 'Aardvark Kitchen', false, true, 'ARCHIVED')");
			// B: nothing plans meals, and "Main kitchen" is taken in another case.
			statement.execute("SELECT pg_temp.kitchen('" + b + "', 'Deity Kitchen', true, false, 'ACTIVE')");
			statement.execute("SELECT pg_temp.kitchen('" + b + "', 'main KITCHEN', false, false, 'ACTIVE')");
			// D: an archived main kitchen, and two open planners created in the opposite order to their names.
			statement.execute("SELECT pg_temp.kitchen('" + d + "', 'Old Main', true, true, 'ARCHIVED')");
			statement.execute("SELECT pg_temp.kitchen('" + d + "', 'Zeta Kitchen', false, true, 'ACTIVE')");
			statement.execute("SELECT pg_temp.kitchen('" + d + "', 'alpha kitchen', false, true, 'ACTIVE')");
		}
	}

	/** The people KitchenOrder is asked about, in temple A, written against the migrated schema. */
	private void seedPeople() throws SQLException {
		if (!strings("SELECT id::text FROM users WHERE firebase_uid = 'uid-wk-a-admin2'").isEmpty()) {
			return;
		}
		String deity = kitchenId(a, "Deity Kitchen");
		String health = kitchenId(a, "Health Kitchen");
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement s = connection.createStatement()) {
			s.execute(user("uid-wk-a-admin2", "TEMPLE_ADMIN", "0101"));
			s.execute(user("uid-wk-a-nostaff", "KITCHEN_STAFF", "0102"));
			s.execute(user("uid-wk-a-deity", "KITCHEN_STAFF", "0103"));
			s.execute(user("uid-wk-a-health", "KITCHEN_STAFF", "0104"));
			s.execute(user("uid-wk-a-former", "KITCHEN_STAFF", "0105"));
			s.execute(staff("uid-wk-a-deity", deity, "ACTIVE"));
			s.execute(staff("uid-wk-a-health", health, "ACTIVE"));
			s.execute(staff("uid-wk-a-former", health, "RESIGNED"));
		}
	}

	private String user(String uid, String role, String phoneTail) {
		return """
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES ('%s', '%s', '%s', '%s@example.com', '+9198765%s', '%s', 'ACTIVE')
				""".formatted(a, uid, uid, uid, phoneTail, role);
	}

	private String staff(String uid, String kitchen, String status) {
		return """
				INSERT INTO staff_profiles (tenant_id, user_id, full_name, job_title, employment_type,
						date_of_joining, employment_status, last_working_day, kitchen_id)
				SELECT '%s', id, full_name, 'COOK', 'FULL_TIME', DATE '2026-01-01', '%s',
					   CASE WHEN '%s' = 'ACTIVE' THEN NULL ELSE DATE '2026-06-01' END, '%s'
				FROM users WHERE firebase_uid = '%s'
				""".formatted(a, status, status, kitchen, uid);
	}

	private interface OrderCheck {
		void check(KitchenOrder order) throws SQLException;
	}

	/**
	 * A {@link KitchenOrder} over one application-role connection with a temple adopted — the same
	 * role and the same setting the request path uses, so row-level security applies to every query.
	 */
	private void withKitchenOrder(String temple, OrderCheck check) throws SQLException {
		seedPeople();
		try (Connection app = appConnection()) {
			try (PreparedStatement ps = app.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
				ps.setString(1, temple);
				ps.executeQuery().close();
			}
			check.check(new KitchenOrder(new JdbcTemplate(new SingleConnectionDataSource(app, true))));
		}
	}

	private static void insertDish(Connection app, String meal, String kitchen) throws SQLException {
		try (PreparedStatement ps = app.prepareStatement("""
				INSERT INTO meal_dishes (tenant_id, meal_id, recipe_id, target_yield, status, created_by, kitchen_id)
				SELECT m.tenant_id, m.id, d.recipe_id, 10, 'PLANNED', d.created_by, ?::uuid
				FROM meals m JOIN meal_dishes d ON d.meal_id = m.id
				WHERE m.id = ?::uuid LIMIT 1
				""")) {
			ps.setString(1, kitchen);
			ps.setString(2, meal);
			ps.executeUpdate();
		}
	}

	private String kitchenId(String temple, String name) throws SQLException {
		List<String> ids = strings("SELECT id::text FROM kitchens WHERE tenant_id = '" + temple + "' AND name = '"
				+ name.replace("'", "''") + "'");
		assertThat(ids).as("kitchen %s", name).hasSize(1);
		return ids.get(0);
	}

	private String firstMealOf(String temple) throws SQLException {
		return strings("""
				SELECT m.id::text FROM meals m JOIN meal_plan_days pd ON pd.id = m.meal_plan_day_id
				WHERE m.tenant_id = '%s' ORDER BY pd.plan_date LIMIT 1
				""".formatted(temple)).get(0);
	}

	private UUID userId(String uid) throws SQLException {
		return UUID.fromString(strings("SELECT id::text FROM users WHERE firebase_uid = '" + uid + "'").get(0));
	}

	/** The application role, adopting a temple the way TenantAwareDataSource does: transaction-local. */
	private static void adopt(Connection app, String temple) throws SQLException {
		try (PreparedStatement ps = app.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
			ps.setString(1, temple);
			ps.executeQuery().close();
		}
	}

	private static String oneString(Connection connection, String sql) throws SQLException {
		try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
			assertThat(rs.next()).as("a row from: %s", sql).isTrue();
			return rs.getString(1);
		}
	}

	private static long count(Connection connection, String sql) throws SQLException {
		try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		}
	}

	private long countOf(String sql) throws SQLException {
		try (Connection connection = superuserConnectionTo(DATABASE)) {
			return count(connection, sql);
		}
	}

	private List<String> strings(String sql) throws SQLException {
		List<String> out = new ArrayList<>();
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement s = connection.createStatement();
				ResultSet rs = s.executeQuery(sql)) {
			while (rs.next()) {
				out.add(rs.getString(1));
			}
		}
		return out;
	}

	/** Migrates as the unprivileged migration role, to a version or (null) to the end. */
	private void migrate(String target) {
		var config = Flyway.configure()
				.dataSource(urlFor(DATABASE), MIGRATION_ROLE, MIGRATION_PASSWORD)
				.locations("classpath:db/migration");
		if (target != null) {
			config.target(target);
		}
		config.load().migrate();
	}

	private void recreateDatabase() throws SQLException {
		try (Connection connection = adminConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
			statement.execute("CREATE DATABASE " + DATABASE);
		}
		// The migration role owns the schema here too, exactly as it does in the real one, so a
		// migration that only works when row-level security is bypassed still fails.
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement statement = connection.createStatement()) {
			statement.execute("ALTER SCHEMA public OWNER TO " + MIGRATION_ROLE);
		}
	}

	private static Connection appConnection() throws SQLException {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl(urlFor(DATABASE));
		dataSource.setUsername(APP_ROLE);
		dataSource.setPassword(APP_PASSWORD);
		return dataSource.getConnection();
	}

	private static Connection superuserConnectionTo(String database) throws SQLException {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl(urlFor(database));
		dataSource.setUsername(POSTGRES.getUsername());
		dataSource.setPassword(POSTGRES.getPassword());
		return dataSource.getConnection();
	}

	private static String urlFor(String database) {
		return "jdbc:postgresql://%s:%d/%s".formatted(
				POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), database);
	}
}
