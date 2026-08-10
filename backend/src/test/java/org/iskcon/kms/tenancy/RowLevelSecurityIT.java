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
 * inherits whatever guarantee is established here, so the assertions are written to fail if
 * isolation is ever weakened — including in the way it is most likely to be weakened in
 * practice: someone forgetting a WHERE clause.
 *
 * <p>Two connections are in play, and the distinction is the whole point. Fixtures are created
 * through {@code admin}, a privileged connection that can seed rows for several tenants at
 * once. Every assertion runs through {@code jdbc}, the application's own tenant-aware
 * DataSource connecting as an unprivileged role — the same path production uses.
 */
class RowLevelSecurityIT extends AbstractIntegrationTest {

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;
	private JdbcTemplate admin;

	private UUID templeA;
	private UUID templeB;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		jdbc = new JdbcTemplate(dataSource);
		admin = new JdbcTemplate(adminDataSource());

		// A tenant-owned table stood up exactly the way every future table will be: a
		// tenant_id column plus the shared policy from the migration.
		admin.execute("""
				CREATE TABLE IF NOT EXISTS recipes (
					id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
					tenant_id UUID NOT NULL REFERENCES tenants(id),
					name      TEXT NOT NULL
				)
				""");
		admin.execute("SELECT enable_tenant_rls('recipes')");
		admin.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON recipes TO " + APP_ROLE);

		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");

		seedRecipe(templeA, "Khichdi");
		seedRecipe(templeA, "Halwa");
		seedRecipe(templeB, "Payasam");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DROP TABLE IF EXISTS recipes");
		admin.execute("DELETE FROM tenants");
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
		// The acceptance criterion from the story. This is the bug that will eventually get
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

		// Must return empty, not raise. PostgreSQL leaves a custom setting as '' after RESET
		// rather than unset, so a naive policy casting that to uuid throws instead of denying.
		// A control that errors is a control someone eventually disables.
		assertThat(recipeNames())
				.as("an unconfigured connection must fail closed, quietly")
				.isEmpty();
	}

	@Test
	@DisplayName("a tenant cannot insert rows belonging to another tenant")
	void cannotInsertForAnotherTenant() {
		TenantContext.set(templeA);

		assertThatThrownBy(() ->
				jdbc.update("INSERT INTO recipes (tenant_id, name) VALUES (?, ?)", templeB, "Smuggled Payasam"))
				.as("WITH CHECK must reject a write attributed to a different tenant")
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
	@DisplayName("the super-admin claim escape widens UPDATE only — it cannot mint a super-admin")
	void claimEscapeCannotInsertASuperAdmin() {
		// The V8 escape lets first sign-in *bind* an existing pending super-admin. It must never
		// let the app *create* one: minting a platform operator stays a privileged, out-of-band
		// act. A verified contact is set, exactly as during a real claim.
		TenantContext.setClaimContact("operator@platform.example");
		try {
			assertThatThrownBy(() -> jdbc.update("""
					INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
					VALUES (NULL, 'pending:rogue', 'Rogue Operator', 'operator@platform.example',
							'+919800000009', 'SUPER_ADMIN', 'ACTIVE')
					"""))
					.as("a FOR UPDATE escape must not open an INSERT path to a tenantless row")
					.isInstanceOf(Exception.class);
		} finally {
			TenantContext.clearClaimContact();
		}
	}

	@Test
	@DisplayName("the super-admin claim escape binds only the row matching the verified contact")
	void claimEscapeAdoptsOnlyTheMatchingContact() {
		UUID pending = admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, 'pending:sa', 'Platform Operator', 'operator@platform.example',
						'+919800000010', 'SUPER_ADMIN', 'ACTIVE')
				RETURNING id
				""", UUID.class);
		try {
			// Reproduce the adopt() context exactly: the caller's real uid is app.auth_uid (set by
			// the filter), and the row is being bound to that same uid. Without this the adopted row
			// would fall out of the read policy and Postgres would refuse the update.
			TenantContext.setAuthLookupUid("real-superadmin-uid");

			// A different verified contact than the row's must bind nothing.
			TenantContext.setClaimContact("someone-else@platform.example");
			int mismatched = jdbc.update(
					"UPDATE users SET firebase_uid = 'real-superadmin-uid' WHERE id = ? AND firebase_uid LIKE 'pending:%'",
					pending);
			assertThat(mismatched).as("a non-matching contact must bind nothing").isZero();

			// The row's own verified contact binds exactly one row.
			TenantContext.setClaimContact("operator@platform.example");
			int bound = jdbc.update(
					"UPDATE users SET firebase_uid = 'real-superadmin-uid' WHERE id = ? AND firebase_uid LIKE 'pending:%'",
					pending);
			assertThat(bound).as("the matching contact binds the pending super-admin").isEqualTo(1);
		} finally {
			TenantContext.clear();
			admin.update("DELETE FROM users WHERE id = ?", pending);
		}
	}

	@Test
	@DisplayName("platform audit is readable by a super-admin and hidden from temple users")
	void platformAuditIsSuperAdminOnly() {
		// Two signed-in identities: a platform operator (no tenant) and a temple admin.
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, 'sa-uid', 'Operator', 'op@platform.example', '+919800000020', 'SUPER_ADMIN', 'ACTIVE')
				""");
		UUID operator = admin.queryForObject("SELECT id FROM users WHERE firebase_uid = 'sa-uid'", UUID.class);
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'ta-uid', 'Admin', 'admin@govinda.example', '+919800000021', 'TEMPLE_ADMIN', 'ACTIVE')
				""", templeA);
		admin.update("""
				INSERT INTO platform_audit_events (actor_user_id, actor_label, action, entity_type, entity_id)
				VALUES (?, 'Operator', 'ACCOUNT_CLAIMED', 'USER', ?)
				""", operator, operator);

		try {
			// The super-admin sees the platform log.
			TenantContext.setAuthLookupUid("sa-uid");
			assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_audit_events", Integer.class))
					.as("a super-admin reads the platform audit log").isEqualTo(1);

			// A temple admin, with a perfectly valid identity and their own tenant set, sees nothing:
			// the gate is the role, not the tenant.
			TenantContext.clear();
			TenantContext.set(templeA);
			TenantContext.setAuthLookupUid("ta-uid");
			assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_audit_events", Integer.class))
					.as("a temple user must not see platform audit").isZero();
		} finally {
			TenantContext.clear();
			admin.update("DELETE FROM platform_audit_events");
			admin.update("DELETE FROM users WHERE firebase_uid IN ('sa-uid', 'ta-uid')");
		}
	}

	@Test
	@DisplayName("switching tenants on a pooled connection does not leak the previous tenant")
	void pooledConnectionDoesNotLeakTenant() {
		// Connections are reused. If the tenant setting were not reset when a connection
		// returns to the pool, this second read would still be scoped to temple A.
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
		return admin.queryForObject(
				"""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""",
				UUID.class, slug, name);
	}

	private void seedRecipe(UUID tenantId, String name) {
		admin.update("INSERT INTO recipes (tenant_id, name) VALUES (?, ?)", tenantId, name);
	}
}
