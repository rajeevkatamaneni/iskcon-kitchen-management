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
 * V97 run against two temples whose delivery events were already pinned to the Gulf of Guinea
 * (T-048).
 *
 * <p><b>Why this exists.</b> T-044 stopped the composer inventing {@code 0,0} and stopped the server
 * believing it, but the rows written between 2026-09-05 and that fix are still in the database. They
 * do not fully heal on their own: T-044's {@code fresh()} makes a poisoned row stale, and a stale
 * row falls back to geocoding the address <em>text</em> — which is exactly what the place picker
 * exists because it often cannot do. So the coordinates have to be cleared, and cleared in a way
 * that leaves {@code delivery_place_id} standing, because the place id is what turns "stale" into a
 * lossless re-resolution from Places rather than a fresh guess at a sub-premise.
 *
 * <p><b>Why it builds its own database.</b> The suite migrates an empty one, where {@code FOR … IN
 * SELECT id FROM tenants} runs its body zero times and PL/pgSQL never plans the statements inside —
 * so a backfill can be syntactically broken, or clear nothing at all, and still be green. That cost
 * a crash-looping deployment once already (see {@link org.iskcon.kms.TenantLoopMigrationIT}). This
 * follows {@link CateringMigrationIT}: build a throwaway database, migrate it to the version before
 * V97, put rows into it in the shapes that actually exist, run the rest on top, and read them back.
 *
 * <p><b>Why two temples.</b> {@code meal_plans} carries FORCE ROW LEVEL SECURITY and the migration
 * role is unprivileged, so the failure mode this test has to exclude is not an exception — it is
 * silence. A blanket {@code UPDATE} with {@code app.tenant_id} unset matches nothing and reports
 * success; a loop that adopted only the first tenant would repair one temple and leave the next
 * one's driver following a pin into the Atlantic. Both of those pass with one temple in the
 * database, so there are two, each holding a poisoned row and a genuine one.
 *
 * <p><b>The fresh-database half</b> of "applies cleanly" is proved by this class starting at all:
 * every {@code AbstractIntegrationTest} boots a Spring context whose Flyway has just run the whole
 * history, V97 included, against an empty database. A migration that failed there would fail every
 * integration test in the suite before a single assertion here ran.
 */
class DeliveryPinBackfillIT extends AbstractIntegrationTest {

	/**
	 * The version V97 was reserved to follow. Seeding here rather than at the end means V97 is
	 * planned and executed against real rows, at the schema version it was written for.
	 */
	private static final String BEFORE_THE_BACKFILL = "96";

	private static final String DATABASE = "kms_delivery_pin_check";

	/** Seeded onto every fixture row so "was this row touched at all?" is answerable exactly. */
	private static final String SEEDED_AT = "2026-09-05 09:15:00+05:30";

	@Test
	@DisplayName("a delivery event stranded at 0,0 loses its coordinates and keeps its place id")
	void poisonedPinsAreClearedAndThePickSurvives() throws SQLException {
		recreateDatabase();

		Flyway.configure()
				.dataSource(url(), MIGRATION_ROLE, MIGRATION_PASSWORD)
				.locations("classpath:db/migration")
				.target(BEFORE_THE_BACKFILL)
				.load()
				.migrate();

		seedTwoTemplesWithDeliveryEvents();

		var result = Flyway.configure()
				.dataSource(url(), MIGRATION_ROLE, MIGRATION_PASSWORD)
				.locations("classpath:db/migration")
				.load()
				.migrate();

		assertThat(result.migrationsExecuted)
				.as("if nothing is left to migrate, this test has stopped testing V97")
				.isPositive();

		// --- 1. the poisoned row, which is the whole point ----------------------
		//
		// Coordinates and geocoded_at gone; delivery_place_id standing. That pairing IS the
		// recovery: isPlaced() now reads false, the row is stale, and MealPlanService re-resolves
		// the pin from Places by this id — one call, and the pick the planner made comes back
		// exactly. Clearing the place id as well would have converted a recoverable row into an
		// address string and a hope.
		assertThat(pinOf("gulf-temple", "Poisoned pick"))
				.as("the Gulf of Guinea should be gone, and the pick that recovers it should not be")
				.isEqualTo("(none)|(none)|(none)|place-poisoned-a");

		// And the repair must not read as an edit. Nothing about the event changed — somebody's
		// address, contact and serving time are all still theirs — so updated_at stays where the
		// planner left it rather than dating this migration.
		assertThat(one("""
				SELECT delivery_address || '|' || (updated_at = TIMESTAMPTZ '%s')
				FROM meal_plans WHERE event_name = 'Poisoned pick' AND delivery_place_id = 'place-poisoned-a'
				""".formatted(SEEDED_AT)))
				.as("the address should be untouched and the row should not date itself to the backfill")
				.isEqualTo("Mantri Serenity, Kanakapura Main Rd, Bengaluru 560062|true");

		// --- 2. the poisoned row that has no place id ---------------------------
		//
		// Cleared as well. This is the row that shows which WHERE was written: filtering to zeroes
		// AND no place id — proposed, rejected — would have cleared THIS one and left the row above,
		// which is precisely backwards. Every row the defect poisoned carries a real place id,
		// because the old composer sent one beside the placeholder zeroes.
		assertThat(pinOf("gulf-temple", "Poisoned orphan"))
				.as("zeroes are false whether or not a place id sits beside them")
				.isEqualTo("(none)|(none)|(none)|(none)");

		// --- 3. a real pin is not touched at all --------------------------------
		assertThat(pinOf("gulf-temple", "Genuine pick"))
				.as("a pin somebody actually chose must come through completely unchanged")
				.isEqualTo("12.856230|77.548110|" + SEEDED_AT + "|place-real-a");

		// --- 4. the single-zero case: DELIBERATELY LEFT ALONE -------------------
		//
		// The decision is argued in V97's comment. In short: the defect's signature is both axes
		// zero together; 0 is a legal coordinate on either axis alone (equator, Greenwich); and
		// T-044's isCoordinate() already refuses a zero on either axis, so these rows are ALREADY
		// unplaced and already re-resolved on the next read. Nulling them would change nothing the
		// application does and would destroy a datum a person might want to look at.
		//
		// These two assertions are the ones that fail if somebody later "tidies" the WHERE into
		// `latitude = 0 OR longitude = 0`, so they are here to make that a deliberate change rather
		// than an accident.
		assertThat(pinOf("gulf-temple", "On the equator"))
				.as("one zero axis is not this defect and is not ours to erase")
				.isEqualTo("0.000000|77.548110|" + SEEDED_AT + "|place-equator");
		assertThat(pinOf("gulf-temple", "On the meridian"))
				.as("nor is the other")
				.isEqualTo("12.856230|0.000000|" + SEEDED_AT + "|place-meridian");

		// --- 5. a row that was never located ------------------------------------
		//
		// Already null on both axes. The backfill must pass over it rather than matching it: NULL
		// is not 0, and an event still waiting for its first lookup is not damage.
		assertThat(pinOf("gulf-temple", "Never located"))
				.as("an unlocated event is not poisoned, and its place id is its way out")
				.isEqualTo("(none)|(none)|(none)|place-unlocated");

		// --- 6. the second temple — the RLS assertion ---------------------------
		//
		// A blanket UPDATE fails closed and clears nothing; a loop that adopts one tenant clears
		// only the first. Both leave this row sitting at 0,0, and both would have passed everything
		// above if this temple did not exist.
		assertThat(pinOf("second-temple", "Poisoned pick"))
				.as("every temple's poisoned rows are cleared, not just the first one the loop saw")
				.isEqualTo("(none)|(none)|(none)|place-poisoned-b");
		assertThat(pinOf("second-temple", "Genuine pick"))
				.as("and the second temple's real pin is as untouched as the first's")
				.isEqualTo("13.100000|77.600000|" + SEEDED_AT + "|place-real-b");

		// --- 7. and the totals, across both temples -----------------------------
		assertThat(count("SELECT count(*) FROM meal_plans"
				+ " WHERE delivery_latitude = 0 AND delivery_longitude = 0"))
				.as("no row anywhere may still claim to be in the Atlantic")
				.isZero();
		assertThat(count("SELECT count(*) FROM meal_plans"
				+ " WHERE delivery_latitude = 0 OR delivery_longitude = 0"))
				.as("and exactly the two single-zero rows should have survived")
				.isEqualTo(2);
		assertThat(count("SELECT count(*) FROM meal_plans WHERE delivery_place_id IS NOT NULL"))
				.as("seven of the eight rows were seeded with a place id and all seven must still have one")
				.isEqualTo(7);
	}

	// ---------------------------------------------------------------------

	/**
	 * Two temples, each with the people and the recipe a plan needs, and eight delivery events
	 * between them covering every shape the {@code WHERE} has to decide about.
	 *
	 * <p>Seeded through the superuser: what is under test is the migration, not the seed.
	 */
	private void seedTwoTemplesWithDeliveryEvents() throws SQLException {
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement statement = connection.createStatement()) {

			temple(statement, "gulf-temple", "Gulf Temple", "uid-pin-a", "+919876500201");
			temple(statement, "second-temple", "Second Temple", "uid-pin-b", "+919876500202");

			// --- the temple the defect reached ---------------------------------
			//
			// A pick that was edited after 2026-09-05 and re-pinned to the placeholder. The place id
			// is real, which is what makes it recoverable and what makes the "no place id" filter
			// wrong.
			event(statement, "gulf-temple", "Poisoned pick", "'place-poisoned-a'", "0", "0", true);
			// The same damage on a row whose place id had already been lost some other way. Nothing
			// recovers this pin, which is all the more reason not to leave it asserting the Atlantic.
			event(statement, "gulf-temple", "Poisoned orphan", "NULL", "0", "0", true);
			// A pin from a pick that was never edited: Mantri Serenity, off Kanakapura Main Road.
			event(statement, "gulf-temple", "Genuine pick", "'place-real-a'",
					"12.856230", "77.548110", true);
			// The two coordinates that are legal on their own. Not this defect; left alone.
			event(statement, "gulf-temple", "On the equator", "'place-equator'",
					"0", "77.548110", true);
			event(statement, "gulf-temple", "On the meridian", "'place-meridian'",
					"12.856230", "0", true);
			// Picked but never looked up — no coordinates and no geocoded_at at all.
			event(statement, "gulf-temple", "Never located", "'place-unlocated'",
					"NULL", "NULL", false);

			// --- and the temple that proves the loop is a loop -------------------
			event(statement, "second-temple", "Poisoned pick", "'place-poisoned-b'", "0", "0", true);
			event(statement, "second-temple", "Genuine pick", "'place-real-b'",
					"13.100000", "77.600000", true);
		}
	}

	/** A temple, someone to have created the plans, a category and a recipe to plan. */
	private void temple(Statement statement, String slug, String name, String uid, String phone)
			throws SQLException {

		statement.execute("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('%s', '%s', 12.97, 77.59, 'Asia/Kolkata')
				""".formatted(slug, name));
		statement.execute("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				SELECT id, '%s', 'Pin Planner', '%s@example.com', '%s', 'TEMPLE_ADMIN', 'ACTIVE'
				FROM tenants WHERE slug = '%s'
				""".formatted(uid, uid, phone, slug));
		statement.execute("""
				INSERT INTO recipe_categories (tenant_id, name)
				SELECT id, 'Rice' FROM tenants WHERE slug = '%s'
				""".formatted(slug));
		statement.execute("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				SELECT t.id, 'Khichdi', c.id, 100, 'KG'
				FROM tenants t JOIN recipe_categories c ON c.tenant_id = t.id
				WHERE t.slug = '%s'
				""".formatted(slug));
	}

	/**
	 * One delivery event, in the shape V93 left the row: a picked address, a sub-location, a contact
	 * and a serving time, plus whatever pin the argument says it is carrying.
	 *
	 * @param placeId SQL literal, quoted, or {@code NULL}
	 * @param latitude SQL literal or {@code NULL}
	 * @param longitude SQL literal or {@code NULL}
	 * @param located whether a lookup was ever recorded — geocoded_at is what V97 clears alongside
	 *     the coordinates, so a row that has one and a row that does not both need to be here
	 */
	private void event(
			Statement statement, String slug, String eventName, String placeId,
			String latitude, String longitude, boolean located)
			throws SQLException {

		statement.execute("""
				INSERT INTO meal_plans (tenant_id, plan_date, meal_kind, ready_by, recipe_id,
						target_yield, day_type, status, adults, children, seniors,
						event_name, is_outside, handover, contact_name, contact_phone,
						delivery_address, delivery_sub_location, guests_eat_at,
						delivery_place_id, delivery_latitude, delivery_longitude, geocoded_at,
						travel_minutes, travel_minutes_source, created_by, created_at, updated_at)
				SELECT t.id, DATE '2026-09-12', 'Event', TIME '11:00', r.id,
						120, 'REGULAR', 'PLANNED', 120, 0, 0,
						'%s', true, 'DELIVERY', 'Mrs Latha Rao', '+919000000001',
						'Mantri Serenity, Kanakapura Main Rd, Bengaluru 560062', 'Clubhouse',
						TIME '13:00',
						%s, %s, %s, %s,
						45, 'MANUAL', u.id, TIMESTAMPTZ '%s', TIMESTAMPTZ '%s'
				FROM tenants t
				JOIN recipes r ON r.tenant_id = t.id
				JOIN users u ON u.tenant_id = t.id
				WHERE t.slug = '%s'
				""".formatted(eventName, placeId, latitude, longitude,
						located ? "TIMESTAMPTZ '" + SEEDED_AT + "'" : "NULL",
						SEEDED_AT, SEEDED_AT, slug));
	}

	// ---------------------------------------------------------------------

	/**
	 * The four columns this migration is about, for one temple's event, as
	 * {@code latitude|longitude|geocoded_at|place_id} with {@code (none)} for a null.
	 *
	 * <p>Read straight off the table through the superuser rather than through any endpoint: what is
	 * under test is what the migration wrote, and a service that re-resolved a stale pin on the way
	 * out would hide exactly the failure this is looking for.
	 */
	private String pinOf(String slug, String eventName) throws SQLException {
		return one("""
				SELECT coalesce(mp.delivery_latitude::text, '(none)')
					|| '|' || coalesce(mp.delivery_longitude::text, '(none)')
					|| '|' || coalesce(to_char(mp.geocoded_at AT TIME ZONE 'Asia/Kolkata',
							'YYYY-MM-DD HH24:MI:SS') || '+05:30', '(none)')
					|| '|' || coalesce(mp.delivery_place_id, '(none)')
				FROM meal_plans mp JOIN tenants t ON t.id = mp.tenant_id
				WHERE t.slug = '%s' AND mp.event_name = '%s'
				""".formatted(slug, eventName));
	}

	private void recreateDatabase() throws SQLException {
		try (Connection connection = adminConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
			statement.execute("CREATE DATABASE " + DATABASE);
		}
		// The migration role owns the schema here as it does in production, so a backfill that only
		// works when RLS is bypassed fails here instead of on a temple.
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
