package org.iskcon.kms.meal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.iskcon.kms.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V88 run against a temple that actually did catering (E4-S15 D7).
 *
 * <p><b>Why this exists.</b> The highest-stakes thing in this whole story is not the new form, it is
 * the rows that were already there. Those are meals the temple cooked, for people it cooked them
 * for, and their client, phone and venue are what somebody goes looking for a year later. Deleting
 * them was rejected outright, and a migration that quietly drops one would look exactly like a
 * migration that worked.
 *
 * <p>The suite migrates an empty database, so nothing else in it can see this. Following
 * {@link org.iskcon.kms.TenantLoopMigrationIT}, this builds a throwaway database, migrates it to the
 * schema version <em>before</em> the change, puts a temple with a catering order and an outside event
 * into it — in the shape those rows actually had — and then runs V88 on top and reads them back.
 *
 * <p>It also proves the loop body itself runs. meal_kinds and meal_plans both carry FORCE ROW LEVEL
 * SECURITY, so a cross-tenant UPDATE inside a migration silently matches nothing and reports
 * success; on an empty database the {@code DO} block's body is never planned and never executed, and
 * the first temple to see it would be a real one.
 */
class CateringMigrationIT extends AbstractIntegrationTest {

	/** The last version before events arrived. Everything the fold reads exists here. */
	private static final String BEFORE_EVENTS = "86";

	private static final String DATABASE = "kms_catering_fold_check";

	@Test
	@DisplayName("a catering plan survives as an outside event with its client, contact and venue intact")
	void cateringPlansSurviveTheFold() throws SQLException {
		recreateDatabase();

		Flyway.configure()
				.dataSource(url(), MIGRATION_ROLE, MIGRATION_PASSWORD)
				.locations("classpath:db/migration")
				.target(BEFORE_EVENTS)
				.load()
				.migrate();

		seedATempleThatCaters();

		Flyway.configure()
				.dataSource(url(), MIGRATION_ROLE, MIGRATION_PASSWORD)
				.locations("classpath:db/migration")
				.load()
				.migrate();

		// --- the catering order ------------------------------------------------
		//
		// Everything it carried, under the words the story renamed them to. A blank in any of these
		// is the Blocker UAT-086 step 40 is hunting for.
		assertThat(one("""
				SELECT meal_kind || '|' || is_outside || '|' || event_name || '|' || contact_name
					|| '|' || contact_phone || '|' || delivery_address || '|' || day_type
					|| '|' || adults || '|' || target_yield || '|' || ready_by
				FROM meal_plans WHERE purpose = 'Sharma wedding reception'
				"""))
				.as("the catering order should be an Event going outside, with everything it had")
				.isEqualTo("Event|true|Sharma wedding reception|Sharma family|+919876500011"
						+ "|Community Hall, Rajajinagar|WEEKEND|200|200.000|11:00:00");

		// --- the outside event -------------------------------------------------
		assertThat(one("""
				SELECT meal_kind || '|' || is_outside || '|' || event_name || '|' || delivery_address
					|| '|' || day_type
				FROM meal_plans WHERE purpose = 'Bhagavad-gita reading'
				"""))
				.as("an outside event folds in the same way, and a Wednesday is a regular day")
				.isEqualTo("Event|true|Bhagavad-gita reading|Jayanagar school hall|REGULAR");

		// A plan that had no purpose to promote keeps an empty name rather than being given an
		// invented one. The planner is asked for it the next time the plan is edited.
		assertThat(one("""
				SELECT coalesce(event_name, '(none)') FROM meal_plans WHERE contact_name = 'Mr Anonymous'
				"""))
				.isEqualTo("(none)");

		// --- and the lunch is exactly as it was --------------------------------
		assertThat(one("SELECT meal_kind || '|' || is_outside || '|' || day_type"
				+ " FROM meal_plans WHERE meal_kind = 'Lunch'"))
				.as("nothing that was not going outside should have been touched")
				.isEqualTo("Lunch|false|REGULAR");

		// --- the kinds ----------------------------------------------------------
		assertThat(count("SELECT count(*) FROM meal_kinds WHERE lower(name) IN"
				+ " ('catering order', 'outside event')"))
				.as("neither old kind may survive anywhere")
				.isZero();
		assertThat(count("SELECT count(*) FROM meal_kinds WHERE lower(name) = 'event' AND is_event"))
				.as("and the temple should have exactly one Event kind to plan with")
				.isEqualTo(1);

		// A temple's own kind that had nothing to do with going outside is left alone, whatever it is
		// called. The fold is about the shape, not about the word.
		assertThat(count("SELECT count(*) FROM meal_kinds WHERE name = 'Prasadam counter'"))
				.isEqualTo(1);

		// --- and CATERING is out of the vocabulary ------------------------------
		assertThat(count("SELECT count(*) FROM meal_plans WHERE day_type = 'CATERING'")).isZero();
	}

	// ---------------------------------------------------------------------

	/**
	 * A temple, a cook, a recipe, four meal kinds in the shape V48 and V64 left them, and four plans:
	 * a catering order on a Saturday, an outside event on a Wednesday, an outside event nobody ever
	 * wrote a purpose for, and an ordinary lunch that must come through untouched.
	 *
	 * <p>Seeded through the superuser: what is under test is the migration, not the seed.
	 */
	private void seedATempleThatCaters() throws SQLException {
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement statement = connection.createStatement()) {

			statement.execute("""
					INSERT INTO tenants (slug, name, latitude, longitude, timezone)
					VALUES ('catering-temple', 'Catering Temple', 12.97, 77.59, 'Asia/Kolkata')
					""");
			statement.execute("""
					INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
					SELECT id, 'uid-fold-cook', 'Fold Cook', 'fold-cook@example.com',
						   '+919876500113', 'KITCHEN_STAFF', 'ACTIVE' FROM tenants WHERE slug = 'catering-temple'
					""");
			statement.execute("""
					INSERT INTO recipe_categories (tenant_id, name)
					SELECT id, 'Rice' FROM tenants WHERE slug = 'catering-temple'
					""");
			statement.execute("""
					INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
					SELECT t.id, 'Khichdi', c.id, 100, 'KG'
					FROM tenants t JOIN recipe_categories c ON c.tenant_id = t.id
					WHERE t.slug = 'catering-temple'
					""");

			// The kinds as V48 and V64 left them, plus one of the temple's own that has nothing to do
			// with going outside and must therefore survive.
			statement.execute("""
					INSERT INTO meal_kinds (tenant_id, name, sort_order, default_ready_time,
							needs_client, needs_venue, needs_purpose, needs_occasion)
					SELECT t.id, v.name, v.sort_order, v.ready_time, v.needs_client, v.needs_venue,
						   v.needs_purpose, false
					FROM tenants t, (VALUES
							('Lunch',           20, TIME '12:00', false, false, false),
							('Catering order',  50, NULL::time,   true,  true,  false),
							('Outside event',   60, NULL::time,   false, true,  true),
							('Prasadam counter', 70, TIME '18:00', false, false, false)
						) AS v(name, sort_order, ready_time, needs_client, needs_venue, needs_purpose)
					WHERE t.slug = 'catering-temple'
					""");

			// 2025-03-22 is a Saturday and 2025-03-19 a Wednesday, so the day type each ends up with
			// is checkable rather than merely plausible.
			plan(statement, "Catering order", "2025-03-22", "11:00", "CATERING",
					"'Sharma family'", "'+919876500011'", "'Community Hall, Rajajinagar'",
					"'Sharma wedding reception'", 200);
			plan(statement, "Outside event", "2025-03-19", "17:00", "REGULAR",
					"NULL", "NULL", "'Jayanagar school hall'", "'Bhagavad-gita reading'", 80);
			plan(statement, "Outside event", "2025-03-19", "18:00", "REGULAR",
					"'Mr Anonymous'", "'+919876500012'", "'Somewhere'", "NULL", 40);
			plan(statement, "Lunch", "2025-03-19", "12:00", "REGULAR",
					"NULL", "NULL", "NULL", "NULL", 300);
		}
	}

	private void plan(
			Statement statement, String kind, String date, String readyBy, String dayType,
			String clientName, String clientContact, String venue, String purpose, int servings)
			throws SQLException {

		statement.execute("""
				INSERT INTO meal_plans (tenant_id, plan_date, meal_kind, ready_by, recipe_id,
						target_yield, day_type, status, client_name, client_contact, venue, purpose,
						adults, created_by)
				SELECT t.id, DATE '%s', '%s', TIME '%s', r.id, %d, '%s', 'PLANNED',
					   %s, %s, %s, %s, %d, u.id
				FROM tenants t
				JOIN recipes r ON r.tenant_id = t.id
				JOIN users u ON u.tenant_id = t.id
				WHERE t.slug = 'catering-temple'
				""".formatted(date, kind, readyBy, servings, dayType,
						clientName, clientContact, venue, purpose, servings));
	}

	private void recreateDatabase() throws SQLException {
		try (Connection connection = adminConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
			statement.execute("CREATE DATABASE " + DATABASE);
		}
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement statement = connection.createStatement()) {
			statement.execute("ALTER SCHEMA public OWNER TO " + MIGRATION_ROLE);
		}
	}

	private String one(String sql) throws SQLException {
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement statement = connection.createStatement();
				var rs = statement.executeQuery(sql)) {
			assertThat(rs.next()).as("no row came back for: %s", sql).isTrue();
			return rs.getString(1);
		}
	}

	private long count(String sql) throws SQLException {
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement statement = connection.createStatement();
				var rs = statement.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		}
	}

	private static Connection superuserConnectionTo(String database) throws SQLException {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl(urlFor(database));
		dataSource.setUsername(POSTGRES.getUsername());
		dataSource.setPassword(POSTGRES.getPassword());
		return dataSource.getConnection();
	}

	private static String url() {
		return urlFor(DATABASE);
	}

	private static String urlFor(String database) {
		return "jdbc:postgresql://%s:%d/%s".formatted(
				POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), database);
	}
}
