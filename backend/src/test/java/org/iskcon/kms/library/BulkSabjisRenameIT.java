package org.iskcon.kms.library;

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
 * V143 run against temples that already hold a "Catering sabjis" category (T-230).
 *
 * <p><b>Why it builds its own database.</b> The suite migrates an empty one, where {@code FOR … IN
 * SELECT id FROM tenants} runs its body zero times, so a rename that matched nothing, or adopted
 * only the first temple, would still be green. This follows {@code DeliveryPinBackfillIT}: migrate a
 * throwaway database to the version before V143, put categories into it in the shapes that exist,
 * run V143 on top, and read them back through the superuser.
 *
 * <p><b>Why three temples.</b> {@code recipe_categories} is under FORCE ROW LEVEL SECURITY and the
 * migration role is unprivileged, so the failure to exclude is silence: an UPDATE with no tenant set
 * matches nothing and reports success. Two temples with the old name prove the loop reaches both.
 * The third already has a "Bulk sabjis" of its own, where a rename would break the per-temple unique
 * name index and fail the deployment; V143 leaves that temple exactly as it was.
 */
class BulkSabjisRenameIT extends AbstractIntegrationTest {

	private static final String BEFORE_THE_RENAME = "142";

	private static final String THE_RENAME = "143";

	private static final String DATABASE = "kms_bulk_sabjis_check";

	@Test
	@DisplayName("the library import files FHC sabjis under Bulk sabjis")
	void theImportUsesTheNewName() {
		assertThat(CategoryMapping.nameFor("fhc-sabjis", "FHC Sabjis")).isEqualTo("Bulk sabjis");
	}

	@Test
	@DisplayName("every temple's Catering sabjis becomes Bulk sabjis, keeping its id and its recipes")
	void cateringSabjisAreRenamedInEveryTemple() throws SQLException {
		recreateDatabase();

		Flyway.configure()
				.dataSource(url(), MIGRATION_ROLE, MIGRATION_PASSWORD)
				.locations("classpath:db/migration")
				.target(BEFORE_THE_RENAME)
				.load()
				.migrate();

		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement statement = connection.createStatement()) {
			temple(statement, "first-temple", "Catering sabjis");
			temple(statement, "second-temple", "catering Sabjis");
			temple(statement, "clash-temple", "Catering sabjis");
			category(statement, "clash-temple", "Bulk sabjis");
		}

		String firstId = categoryId("first-temple", "catering sabjis");
		String secondId = categoryId("second-temple", "catering sabjis");
		String clashOldId = categoryId("clash-temple", "catering sabjis");
		String clashNewId = categoryId("clash-temple", "bulk sabjis");

		var result = Flyway.configure()
				.dataSource(url(), MIGRATION_ROLE, MIGRATION_PASSWORD)
				.locations("classpath:db/migration")
				.target(THE_RENAME)
				.load()
				.migrate();

		assertThat(result.migrationsExecuted)
				.as("if nothing is left to migrate, this test has stopped testing V143")
				.isEqualTo(1);

		// Both temples renamed: the loop is a loop, and the match ignores case the way the unique
		// index does. The id is kept, so nothing that points at the category moved.
		assertThat(categoryId("first-temple", "bulk sabjis")).isEqualTo(firstId);
		assertThat(categoryId("second-temple", "bulk sabjis")).isEqualTo(secondId);
		assertThat(nameOf(firstId)).isEqualTo("Bulk sabjis");
		assertThat(nameOf(secondId)).isEqualTo("Bulk sabjis");
		assertThat(count("SELECT count(*) FROM recipe_categories"
				+ " WHERE lower(name) = 'catering sabjis' AND tenant_id IN"
				+ " (SELECT id FROM tenants WHERE slug IN ('first-temple', 'second-temple'))"))
				.as("no renamed temple should keep the old name")
				.isZero();
		assertThat(count("SELECT count(*) FROM recipes WHERE name = 'Aloo Gobi' AND category_id IN ('"
				+ firstId + "', '" + secondId + "')"))
				.as("each temple's recipe should still be filed under the renamed category")
				.isEqualTo(2);

		// The temple that already had a Bulk sabjis: left exactly as it was, both rows and both names.
		assertThat(nameOf(clashOldId)).isEqualTo("Catering sabjis");
		assertThat(nameOf(clashNewId)).isEqualTo("Bulk sabjis");
		assertThat(count("SELECT count(*) FROM recipes WHERE category_id = '" + clashOldId + "'"))
				.as("its recipe should not have been moved")
				.isEqualTo(1);

		// Nothing else renamed.
		assertThat(count("SELECT count(*) FROM recipe_categories WHERE name = 'Rice'")).isEqualTo(3);
	}

	// ---------------------------------------------------------------------

	/** A temple with a Rice category and the named sabji category, and one recipe filed under it. */
	private void temple(Statement statement, String slug, String sabjiCategory) throws SQLException {
		statement.execute("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('%s', '%s', 12.97, 77.59, 'Asia/Kolkata')
				""".formatted(slug, slug));
		category(statement, slug, "Rice");
		category(statement, slug, sabjiCategory);
		statement.execute("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit)
				SELECT t.id, 'Aloo Gobi', c.id, 20, 'KG'
				FROM tenants t JOIN recipe_categories c ON c.tenant_id = t.id
				WHERE t.slug = '%s' AND c.name = '%s'
				""".formatted(slug, sabjiCategory));
	}

	private void category(Statement statement, String slug, String name) throws SQLException {
		statement.execute("""
				INSERT INTO recipe_categories (tenant_id, name)
				SELECT id, '%s' FROM tenants WHERE slug = '%s'
				""".formatted(name, slug));
	}

	private String categoryId(String slug, String lowerName) throws SQLException {
		return one("""
				SELECT c.id::text FROM recipe_categories c JOIN tenants t ON t.id = c.tenant_id
				WHERE t.slug = '%s' AND lower(c.name) = '%s'
				""".formatted(slug, lowerName));
	}

	private String nameOf(String categoryId) throws SQLException {
		return one("SELECT name FROM recipe_categories WHERE id = '" + categoryId + "'");
	}

	private long count(String sql) throws SQLException {
		return Long.parseLong(one(sql));
	}

	private String one(String sql) throws SQLException {
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement statement = connection.createStatement();
				var rs = statement.executeQuery(sql)) {
			assertThat(rs.next()).as("expected a row for: " + sql).isTrue();
			return rs.getString(1);
		}
	}

	private void recreateDatabase() throws SQLException {
		try (Connection connection = adminConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
			statement.execute("CREATE DATABASE " + DATABASE);
		}
		// The migration role owns the schema, as it does in the real one, so a migration that only
		// works when RLS is bypassed still fails here.
		try (Connection connection = superuserConnectionTo(DATABASE);
				Statement statement = connection.createStatement()) {
			statement.execute("ALTER SCHEMA public OWNER TO " + MIGRATION_ROLE);
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
