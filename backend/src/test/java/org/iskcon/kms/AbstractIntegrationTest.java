package org.iskcon.kms;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests that need a real PostgreSQL.
 *
 * <p>SYSTEM_DESIGN.md requires tenant isolation to be verified against real database behaviour
 * rather than mocked away — Row-Level Security is a database feature, and only a database can
 * demonstrate it.
 *
 * <p><strong>Three roles, deliberately.</strong> The container's own user is a superuser, and
 * superusers bypass RLS entirely — {@code FORCE ROW LEVEL SECURITY} constrains a table's owner
 * but nothing constrains a superuser. Running tests as that user would make every isolation
 * assertion pass vacuously. So this class mirrors the production topology instead:
 *
 * <ul>
 *   <li>{@code kms_app} — unprivileged, no DDL, no BYPASSRLS. The application connects as this,
 *       so RLS genuinely applies.
 *   <li>{@code kms_migrator} — owns the schema and runs Flyway, and is likewise no superuser.
 *       It is the role Terraform provisions for migrations in real environments.
 *   <li>the container superuser — test fixture setup only, where a test needs to seed rows
 *       across several tenants at once.
 * </ul>
 *
 * <p>The migration role being unprivileged matters as much as the application role being
 * unprivileged, and for the same reason. A migration run by a superuser is exempt from every
 * policy it just created, so seed data and backfills appear to work and then fail — or, worse,
 * silently touch nothing — on a real deployment. That has now cost three hotfix migrations
 * (V45, V46, and the V48 rewrite). Here the migration role is subject to its own policies, so
 * a migration that only works as a superuser fails in the suite instead.
 *
 * <p>The container itself is a JVM-wide singleton started in a static initialiser rather than
 * managed by {@code @Testcontainers}/{@code @Container}: that annotation pair stops the
 * container when a test class finishes, but Spring caches contexts across classes, so a later
 * class would inherit a cached context pointing at a dead container.
 */
// Quartz is excluded here, and re-enabled only in the one test that exercises it. In production
// the scheduler runs solely in the worker; recreating it in every @SpringBootTest context would
// put a dozen schedulers of the same name into one JVM, contending in Quartz's process-wide
// registry. Off by default keeps each test's context to what it actually needs.
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "spring.autoconfigure.exclude="
				+ "org.springframework.boot.autoconfigure.quartz.QuartzAutoConfiguration")
public abstract class AbstractIntegrationTest {

	protected static final String APP_ROLE = "kms_app";
	protected static final String APP_PASSWORD = "kms_app_password";

	protected static final String MIGRATION_ROLE = "kms_migrator";
	protected static final String MIGRATION_PASSWORD = "kms_migrator_password";

	// max_connections is raised well above Postgres's default of 100: every distinct
	// @SpringBootTest context caches its own Hikari pool against this one shared container, and
	// the suite now has enough context variants that the default runs out of connections. Cheap
	// on a throwaway test database.
	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>("postgres:16-alpine")
					.withDatabaseName("kms_test")
					.withUsername("kms_migration")
					.withPassword("kms_migration")
					.withCommand("postgres", "-c", "max_connections=400");

	static {
		POSTGRES.start();
		createUnprivilegedRoles();
	}

	/**
	 * Creates the two unprivileged roles. Both must exist before Flyway runs: the V1 migration
	 * grants privileges to the application role, and the migration role is what runs Flyway.
	 */
	private static void createUnprivilegedRoles() {
		try (Connection connection = adminConnection();
				Statement statement = connection.createStatement()) {

			statement.execute(
					"CREATE ROLE " + APP_ROLE + " WITH LOGIN PASSWORD '" + APP_PASSWORD + "'");

			// Explicitly not a superuser and explicitly subject to RLS. Stated rather than
			// assumed, since these are the two properties the isolation tests depend on.
			statement.execute("ALTER ROLE " + APP_ROLE + " NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE");

			statement.execute(
					"CREATE ROLE " + MIGRATION_ROLE + " WITH LOGIN PASSWORD '" + MIGRATION_PASSWORD + "'");
			statement.execute(
					"ALTER ROLE " + MIGRATION_ROLE + " NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE");

			// It owns the schema, so it may create objects there and grant on them — and so the
			// SECURITY DEFINER functions the migrations create run as it, exactly as in production.
			// PostgreSQL 15 onwards no longer lets just anyone create in `public`, which is why
			// ownership rather than a bare GRANT is the right mirror of the real topology.
			statement.execute("ALTER SCHEMA public OWNER TO " + MIGRATION_ROLE);

		} catch (SQLException e) {
			throw new IllegalStateException("Failed to create the unprivileged roles", e);
		}
	}

	protected static Connection adminConnection() throws SQLException {
		return DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	/**
	 * A privileged DataSource for test fixture setup — creating tables and seeding rows across
	 * tenants. Never used for the assertions themselves; those go through the application's
	 * tenant-aware DataSource so that what is being tested is what actually runs in production.
	 */
	protected static DataSource adminDataSource() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl(POSTGRES.getJdbcUrl());
		dataSource.setUsername(POSTGRES.getUsername());
		dataSource.setPassword(POSTGRES.getPassword());
		return dataSource;
	}

	@DynamicPropertySource
	static void registerPostgresProperties(DynamicPropertyRegistry registry) {
		// The application runs as the unprivileged role.
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", () -> APP_ROLE);
		registry.add("spring.datasource.password", () -> APP_PASSWORD);

		// Migrations run as the schema-owning migration role — not a superuser, so a migration
		// that only works because RLS was bypassed fails here rather than on a deployment.
		// Supplying an explicit url also makes Flyway build its own DataSource rather than
		// deriving one from the primary — which it cannot do, because the primary is wrapped by
		// TenantAwareDataSource.
		registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
		registry.add("spring.flyway.user", () -> MIGRATION_ROLE);
		registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);

		// Schema comes from migrations, exactly as in production — never Hibernate-generated, which
		// would prove nothing about the RLS policies that live in the migrations. Run `validate`
		// (not `none`) so every context load checks the entity mappings against the real migrated
		// schema. `validate` still generates nothing; it only verifies. This is the *only* place
		// that check runs: application.yml uses validate in every real deployment, but no other
		// test exercises it, so entity/column drift would otherwise surface for the first time on a
		// production boot (it did once — a CHAR(3) column mapped as a plain String).
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
		registry.add("spring.flyway.enabled", () -> "true");
	}
}
