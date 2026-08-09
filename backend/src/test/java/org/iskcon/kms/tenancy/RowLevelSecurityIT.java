package org.iskcon.kms.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.iskcon.kms.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves tenant isolation is enforced by PostgreSQL, not by application code.
 *
 * <p>These tests are the reason E1-S3 exists. Every later epic stores tenant-owned data and
 * inherits whatever guarantee is established here — so the assertions are written to fail if
 * isolation is weakened, including in the specific way it is most likely to be weakened: a
 * developer forgetting a WHERE clause.
 *
 * <p>Runs against a real PostgreSQL via Testcontainers. Mocking would prove nothing — RLS is a
 * database behaviour, and only the database can demonstrate it.
 */
class RowLevelSecurityIT extends AbstractIntegrationTest {

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;

	private UUID templeA;
	private UUID templeB;

	@BeforeEach
	void setUp() {
		jdbc = new JdbcTemplate(dataSource);

		// A tenant-owned table stood up the way every future table will be: tenant_id column
		// plus the shared policy from the migration.
		TenantContext.clear();
		jdbc.execute("""
				CREATE TABLE IF NOT EXISTS recipes (
					id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
					tenant_id UUID NOT NULL REFERENCES tenants(id),
					name      TEXT NOT NULL
				)
				""");
		jdbc.execute("SELECT enable_tenant_rls('recipes')");

		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");

		insertRecipe(templeA, "Khichdi");
		insertRecipe(templeA, "Halwa");
		insertRecipe(templeB, "Payasam");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		jdbc.execute("DROP TABLE IF EXISTS recipes");
		jdbc.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a tenant sees only its own rows")
	void tenantSeesOnlyOwnRows() {
		TenantContext.set(templeA);
		assertThat(recipeNames()).containsExactlyInAnyOrder("Khichdi", "Halwa");

		TenantContext.set(templeB);
		assertThat(recipeNames()).containsExactly("Payasam");
	}

	@Test
	@DisplayName("a query with no WHERE clause still returns only the current tenant's rows")
	void unfilteredQueryIsStillIsolated() {
		// The acceptance criterion from the story: this is the bug that will eventually be
		// written, and the database must catch it. There is no tenant filter anywhere in this
		// SQL — isolation comes entirely from the RLS policy.
		TenantContext.set(templeA);
		List<String> all = jdbc.queryForList("SELECT name FROM recipes", String.class);

		assertThat(all)
				.as("an unfiltered SELECT must not leak another temple's data")
				.containsExactlyInAnyOrder("Khichdi", "Halwa")
				.doesNotContain("Payasam");
	}

	@Test
	@DisplayName("no tenant context means no rows, not all rows")
	void withoutTenantContextNothingIsVisible() {
		TenantContext.clear();

		assertThat(recipeNames())
				.as("an unconfigured connection must fail closed")
				.isEmpty();
	}

	@Test
	@DisplayName("a tenant cannot insert rows belonging to another tenant")
	void cannotInsertForAnotherTenant() {
		TenantContext.set(templeA);

		assertThatThrownBy(() -> insertRecipe(templeB, "Smuggled Payasam"))
				.as("WITH CHECK must reject writes attributed to a different tenant")
				.isInstanceOf(Exception.class);
	}

	@Test
	@DisplayName("a tenant cannot update or delete another tenant's rows")
	void cannotModifyAnotherTenantsRows() {
		TenantContext.set(templeA);

		int updated = jdbc.update("UPDATE recipes SET name = 'Hijacked' WHERE name = 'Payasam'");
		int deleted = jdbc.update("DELETE FROM recipes WHERE name = 'Payasam'");

		assertThat(updated).as("cross-tenant UPDATE must affect nothing").isZero();
		assertThat(deleted).as("cross-tenant DELETE must affect nothing").isZero();

		TenantContext.set(templeB);
		assertThat(recipeNames())
				.as("the other temple's data must be untouched")
				.containsExactly("Payasam");
	}

	@Test
	@DisplayName("switching tenants on a pooled connection does not leak the previous tenant")
	void pooledConnectionDoesNotLeakTenant() {
		// Connections are reused. If the tenant setting were not reset on return to the pool,
		// this second read would still be scoped to temple A.
		TenantContext.set(templeA);
		assertThat(recipeNames()).hasSize(2);

		TenantContext.set(templeB);
		assertThat(recipeNames()).containsExactly("Payasam");

		TenantContext.clear();
		assertThat(recipeNames()).isEmpty();
	}

	// ---------------------------------------------------------------------

	private List<String> recipeNames() {
		return jdbc.queryForList("SELECT name FROM recipes", String.class);
	}

	private UUID insertTenant(String slug, String name) {
		return jdbc.queryForObject(
				"""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""",
				UUID.class, slug, name);
	}

	private void insertRecipe(UUID tenantId, String name) {
		jdbc.update("INSERT INTO recipes (tenant_id, name) VALUES (?, ?)", tenantId, name);
	}
}
