package org.iskcon.kms;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The whole migration history, run once more against a database that has a temple in it.
 *
 * <p><b>Why this exists.</b> Tenant-owned data cannot be backfilled in one statement: a data
 * statement inside a migration is subject to the isolation policy the schema declares, so a
 * cross-tenant UPDATE silently matches nothing. The fix is a {@code DO} block that adopts each
 * tenant in turn, and several migrations now have one.
 *
 * <p>But the suite migrates an <em>empty</em> database. {@code FOR … IN SELECT id FROM tenants} over
 * no tenants runs its body zero times, and PL/pgSQL only plans a statement the first time it
 * executes — so everything inside those loops goes unparsed here and is first planned on a real
 * deployment, against a real temple, during boot.
 *
 * <p>V57 shipped exactly that way on 2026-08-19. A table aliased {@code t} inside a block declaring
 * {@code t RECORD} resolved to the record rather than the table; the API crash-looped on startup;
 * and 831 green tests had said nothing, because not one of them had a tenant in the database at the
 * moment the migration ran.
 *
 * <p>So this builds a throwaway database, migrates it as far as the {@code users} table, puts a
 * temple and two people in it, and then runs every remaining migration on top. Each loop body is
 * planned at the schema version it was written for — which replaying them afterwards could never do,
 * since several of them read columns they then drop.
 */
class TenantLoopMigrationIT extends AbstractIntegrationTest {

	/** V2 creates {@code users}; everything a backfill might look for exists from there on. */
	private static final String SEED_POINT = "2";

	private static final String DATABASE = "kms_tenant_loop_check";

	@Test
	@DisplayName("every migration runs against a database that already has a temple in it")
	void migrationsRunWithATenantPresent() throws SQLException {
		recreateDatabase();

		// Up to the point where there is somewhere to put a temple.
		Flyway.configure()
				.dataSource(url(), MIGRATION_ROLE, MIGRATION_PASSWORD)
				.locations("classpath:db/migration")
				.target(SEED_POINT)
				.load()
				.migrate();

		seedATemple();

		// And now the rest, every loop body planned against real rows. A failure here is a migration
		// that would have crash-looped the API on a deployment and passed every other test.
		var result = Flyway.configure()
				.dataSource(url(), MIGRATION_ROLE, MIGRATION_PASSWORD)
				.locations("classpath:db/migration")
				.load()
				.migrate();

		assertThat(result.migrationsExecuted)
				.as("if this stops migrating anything, the guard has quietly stopped guarding")
				.isPositive();

		// The backfills did their work, not merely their syntax: V57 employs everybody who was
		// already staff, because /staff is now the only register they appear on.
		assertThat(countOf("SELECT count(*) FROM staff_profiles"))
				.as("the two seeded staff should have been hired retrospectively")
				.isEqualTo(2);
		assertThat(countOf("SELECT count(*) FROM staff_schedule_template"))
				.as("each of them needs seven days the schedule grid can edit")
				.isEqualTo(14);
	}

	// ---------------------------------------------------------------------

	/**
	 * A temple and the two kinds of person a backfill cares about. Seeded through the superuser:
	 * what is under test is the migrations, not the seed, and a session setting would not survive
	 * from one statement to the next here.
	 */
	private void seedATemple() throws SQLException {
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO tenants (slug, name, latitude, longitude, timezone)
					VALUES ('loop-check', 'Migration Loop Temple', 12.97, 77.59, 'Asia/Kolkata')
					""");
			statement.execute("""
					INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
					SELECT id, 'uid-loop-admin', 'Loop Admin', 'loop-admin@example.com',
						   '+919876500111', 'TEMPLE_ADMIN', 'ACTIVE' FROM tenants WHERE slug = 'loop-check'
					""");
			statement.execute("""
					INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
					SELECT id, 'uid-loop-cook', 'Loop Cook', 'loop-cook@example.com',
						   '+919876500112', 'KITCHEN_STAFF', 'ACTIVE' FROM tenants WHERE slug = 'loop-check'
					""");
		}
	}

	private void recreateDatabase() throws SQLException {
		try (Connection connection = adminConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
			statement.execute("CREATE DATABASE " + DATABASE);
		}
		// The migration role owns the schema here too, exactly as it does in the real one, so a
		// migration that only works when RLS is bypassed still fails.
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement statement = connection.createStatement()) {
			statement.execute("ALTER SCHEMA public OWNER TO " + MIGRATION_ROLE);
		}
	}

	private long countOf(String sql) throws SQLException {
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
