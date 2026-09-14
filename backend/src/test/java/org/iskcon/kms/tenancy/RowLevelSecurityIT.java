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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
		// tenant_id column plus the shared policy from the migration. Deliberately named
		// rls_fixture, not after any real table — a real migration table of the same name would
		// collide with this CREATE/DROP and break the whole class.
		admin.execute("""
				CREATE TABLE IF NOT EXISTS rls_fixture (
					id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
					tenant_id UUID NOT NULL REFERENCES tenants(id),
					name      TEXT NOT NULL
				)
				""");
		admin.execute("SELECT enable_tenant_rls('rls_fixture')");
		admin.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON rls_fixture TO " + APP_ROLE);

		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");

		seedRecipe(templeA, "Khichdi");
		seedRecipe(templeA, "Halwa");
		seedRecipe(templeB, "Payasam");
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DROP TABLE IF EXISTS rls_fixture");
		admin.execute("DELETE FROM tenants");
	}

	/**
	 * The guard that catches the mistake this project is most exposed to: a new tenant-owned table
	 * shipped without {@code enable_tenant_rls()}.
	 *
	 * <p>Every other test here proves that isolation works on tables that have the policy. None of
	 * them notices a table that never got one — and a table without it is not partly isolated, it is
	 * not isolated at all. The 2026-08-20 build added nine tenant-owned tables in one pass, written
	 * in parallel; this asserts that carrying a {@code tenant_id} column and being protected are the
	 * same thing, so the next one cannot quietly be neither.
	 *
	 * <p>FORCE matters as much as ENABLE. Without it the table's owner is exempt, and on this
	 * deployment the migration role owns every table — so a table with ENABLE alone is isolated
	 * against the application and wide open to anything running as the owner.
	 *
	 * <p>Note what this test does <em>not</em> exempt: {@code users}, which carries its own narrower
	 * policies plus the auth-lookup and account-claim escapes (V2, V4, V8) so that somebody can be
	 * resolved before a tenant is known. Those are additional policies on a table that is still
	 * enabled and forced, which is the distinction worth keeping — an escape hatch is not the same
	 * thing as an unlocked door.
	 */
	@Test
	@DisplayName("every table with a tenant_id is covered by row-level security")
	void everyTenantOwnedTableIsProtected() {
		// Two documented exceptions, each stated here rather than in a comment somewhere else.
		List<String> deliberateExceptions = List.of(
				// employment_bans withholds FORCE on purpose (V65): the cross-temple matcher is a
				// SECURITY DEFINER function running as the table's owner, and the owner's exemption
				// is the mechanism that lets it read across temples. Every arm of it demands
				// specific details about one named person, and no argument returns the table.
				"employment_bans",
				// payment_events carries a tenant_id that is nullable and informational, filled in
				// after the fact by the handler that resolves it. A gateway webhook arrives
				// unauthenticated and account-global, before any tenant is known, so the column is a
				// note about which temple the event turned out to concern — not a claim of ownership
				// (V37). No tenant-facing endpoint reads this table.
				"payment_events");

		List<String> unprotected = admin.queryForList("""
				SELECT c.relname
				FROM pg_class c
				JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id'
					AND NOT a.attisdropped
				WHERE c.relkind = 'r'
				  AND c.relnamespace = 'public'::regnamespace
				  AND NOT (c.relrowsecurity AND c.relforcerowsecurity)
				ORDER BY c.relname
				""", String.class);

		assertThat(unprotected)
				.as("these tables carry a tenant_id but are not behind FORCEd row-level security — "
						+ "isolation in this product is a database guarantee, and a table without the "
						+ "policy has none of it")
				.containsExactlyInAnyOrderElementsOf(deliberateExceptions);
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

		List<String> all = jdbc.queryForList("SELECT name FROM rls_fixture", String.class);

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
				jdbc.update("INSERT INTO rls_fixture (tenant_id, name) VALUES (?, ?)", templeB, "Smuggled Payasam"))
				.as("WITH CHECK must reject a write attributed to a different tenant")
				.isInstanceOf(Exception.class);
	}

	@Test
	@DisplayName("a tenant cannot update or delete another tenant's rows")
	void cannotModifyAnotherTenantsRows() {
		TenantContext.set(templeA);

		int updated = jdbc.update("UPDATE rls_fixture SET name = 'Hijacked' WHERE name = 'Payasam'");
		int deleted = jdbc.update("DELETE FROM rls_fixture WHERE name = 'Payasam'");

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

	/**
	 * Pins, as intended, the one place a signed-in request can read rows outside its temple: its own
	 * accounts at other temples.
	 *
	 * <p>The read policy on {@code users} admits {@code firebase_uid = app.auth_uid} (V2, V4) so that
	 * sign-in can find a person's memberships before a temple is chosen, and the temple switcher can
	 * list them afterwards ({@code WhoAmIController.temples}). Since V52 one person may hold an account
	 * at several temples, and the authentication filter leaves the uid set for the whole request, so
	 * this visibility lasts the whole request too. That is the design, not a leak in the policy; the
	 * leak was application queries on {@code users} that trusted RLS to name the temple and so picked
	 * these rows up as well — see {@code OwnAccountsAtOtherTemplesIT}. This test is here so the next
	 * person to notice the visibility reads it as deliberate, and so it cannot quietly widen: it is the
	 * person's own rows only, readable only, and gone the moment the uid is.
	 */
	@Test
	@DisplayName("a signed-in person can read their own accounts at other temples, and change none of them")
	void ownAccountsAtOtherTemplesAreReadableAndNotWritable() {
		UUID atA = insertPerson(templeA, "uid-two-temples", "+919800000031");
		UUID atB = insertPerson(templeB, "uid-two-temples", "+919800000031");
		UUID strangerAtB = insertPerson(templeB, "uid-stranger", "+919800000032");
		try {
			// Signed in at temple A, exactly as AuthenticationFilter leaves the request.
			TenantContext.set(templeA);
			TenantContext.setAuthLookupUid("uid-two-temples");

			assertThat(jdbc.queryForList("SELECT id FROM users", UUID.class))
					.as("this temple's rows plus the caller's own account at temple B — and nobody else's")
					.containsExactlyInAnyOrder(atA, atB)
					.doesNotContain(strangerAtB);

			// The write policies (V8) are temple-only, with no uid branch.
			assertThat(jdbc.update("UPDATE users SET full_name = 'Changed' WHERE id = ?", atB))
					.as("an update to one's own account at another temple affects nothing").isZero();
			assertThat(jdbc.update("DELETE FROM users WHERE id = ?", atB))
					.as("nor does a delete").isZero();
			assertThatThrownBy(() -> jdbc.update("""
					INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
					VALUES (?, 'uid-smuggled', 'Smuggled', 'smuggled@example.com', '+919800000033',
							'VOLUNTEER', 'ACTIVE')
					""", templeB))
					.as("nor can an account be created at the other temple")
					.isInstanceOf(Exception.class);

			// Without the uid only the temple is left: the visibility comes from the escape alone.
			TenantContext.clear();
			TenantContext.set(templeA);
			assertThat(jdbc.queryForList("SELECT id FROM users", UUID.class)).containsExactly(atA);

			assertThat(admin.queryForObject("SELECT full_name FROM users WHERE id = ?", String.class, atB))
					.isEqualTo("Person");
		} finally {
			TenantContext.clear();
			admin.update("DELETE FROM users WHERE id IN (?, ?, ?)", atA, atB, strangerAtB);
		}
	}

	// ---------------------------------------------------------------------
	// The platform audit log's two non-operator escapes name the temple (V133)
	// ---------------------------------------------------------------------
	//
	// V65 and V66 let a temple user append to platform_audit_events for bans and notices, "attributed
	// to themselves", checked as "a users row carrying the caller's uid". Since V52 one uid may have a
	// row at several temples, and the users read policy shows all of them for the whole request — so
	// before V133 a request at temple A could write a platform audit row whose author is the caller's
	// own account at temple B. The application passes the right id; these pin that the database
	// refuses the wrong one regardless. Each is the request exactly as AuthenticationFilter leaves it:
	// the temple set, the uid set, through the unprivileged application connection.
	//
	// Written without RETURNING on purpose: PostgreSQL applies a table's SELECT policy to RETURNING,
	// and a temple user cannot read this table, so a RETURNING insert would be refused for the read
	// and prove nothing about the write.

	/** The three entity types the two escapes cover. */
	private static final List<String> NON_OPERATOR_ENTITY_TYPES =
			List.of("EMPLOYMENT_BAN", "EMPLOYMENT_BAN_CHECK", "PLATFORM_NOTICE");

	// One invocation per entity type rather than a loop, so the two policies are proved independently:
	// in a loop the first refusal that fails to happen ends the test, and a hole in V66 would hide
	// behind one in V65.
	@ParameterizedTest(name = "as {0}")
	@ValueSource(strings = {"EMPLOYMENT_BAN", "EMPLOYMENT_BAN_CHECK", "PLATFORM_NOTICE"})
	@DisplayName("signed in at one temple, a person cannot author a platform audit row as their account at another")
	void platformAuditRefusesTheCallersAccountAtAnotherTemple(String entityType) {
		UUID atA = insertMember(templeA, "uid-audit-two-temples", "+919800000041", "TEMPLE_ADMIN", "ACTIVE");
		UUID atB = insertMember(templeB, "uid-audit-two-temples", "+919800000041", "TEMPLE_ADMIN", "ACTIVE");
		try {
			TenantContext.set(templeA);
			TenantContext.setAuthLookupUid("uid-audit-two-temples");

			// The precondition that makes this a real risk rather than a theoretical one: the account at
			// B is visible to this request, so the policy's EXISTS can find it.
			assertThat(jdbc.queryForList("SELECT id FROM users WHERE id = ?", UUID.class, atB))
					.as("the caller's own account at temple B is readable from temple A (V4, V52)")
					.containsExactly(atB);

			assertThatThrownBy(() -> insertPlatformAudit(atB, entityType))
					.as("a %s row authored by the caller's account at another temple must be refused", entityType)
					.hasStackTraceContaining("row-level security");

			assertThat(admin.queryForObject("SELECT count(*) FROM platform_audit_events", Integer.class))
					.as("nothing was written").isZero();
		} finally {
			TenantContext.clear();
			admin.update("DELETE FROM platform_audit_events");
			admin.update("DELETE FROM users WHERE id IN (?, ?)", atA, atB);
		}
	}

	@Test
	@DisplayName("the same person authoring as their active account at the temple they are signed in at is admitted")
	void platformAuditAdmitsTheCallersActiveAccountHere() {
		UUID atA = insertMember(templeA, "uid-audit-two-temples", "+919800000042", "TEMPLE_ADMIN", "ACTIVE");
		UUID atB = insertMember(templeB, "uid-audit-two-temples", "+919800000042", "TEMPLE_ADMIN", "ACTIVE");
		try {
			TenantContext.set(templeA);
			TenantContext.setAuthLookupUid("uid-audit-two-temples");

			for (String entityType : NON_OPERATOR_ENTITY_TYPES) {
				assertThat(insertPlatformAudit(atA, entityType))
						.as("a %s row authored by this temple's own active account is the legitimate write", entityType)
						.isEqualTo(1);
			}

			assertThat(admin.queryForList(
					"SELECT entity_type FROM platform_audit_events WHERE actor_user_id = ?", String.class, atA))
					.containsExactlyInAnyOrderElementsOf(NON_OPERATOR_ENTITY_TYPES);

			// And the narrowing did not open anything else: an entity type outside the two escapes is
			// still refused for a temple user, even authored correctly.
			assertThatThrownBy(() -> insertPlatformAudit(atA, "USER"))
					.as("a temple user still writes only bans, ban checks and notices here")
					.hasStackTraceContaining("row-level security");
		} finally {
			TenantContext.clear();
			admin.update("DELETE FROM platform_audit_events");
			admin.update("DELETE FROM users WHERE id IN (?, ?)", atA, atB);
		}
	}

	// One invocation per entity type, for the same reason as above. Refused for the notice before V133
	// too (V66 checked status); for the two ban types this is new — so without V133 the notice
	// invocation stays green and the ban invocations go red, which is the expected shape of the control.
	@ParameterizedTest(name = "as {0}")
	@ValueSource(strings = {"EMPLOYMENT_BAN", "EMPLOYMENT_BAN_CHECK", "PLATFORM_NOTICE"})
	@DisplayName("a disabled account at this temple cannot author a platform audit row")
	void platformAuditRefusesADisabledAccountHere(String entityType) {
		UUID disabled = insertMember(templeA, "uid-audit-disabled", "+919800000043", "TEMPLE_ADMIN", "DISABLED");
		try {
			TenantContext.set(templeA);
			TenantContext.setAuthLookupUid("uid-audit-disabled");

			assertThatThrownBy(() -> insertPlatformAudit(disabled, entityType))
					.as("a %s row authored by a DISABLED account must be refused", entityType)
					.hasStackTraceContaining("row-level security");
			assertThat(admin.queryForObject("SELECT count(*) FROM platform_audit_events", Integer.class))
					.isZero();
		} finally {
			TenantContext.clear();
			admin.update("DELETE FROM platform_audit_events");
			admin.update("DELETE FROM users WHERE id = ?", disabled);
		}
	}

	@Test
	@DisplayName("an operator with no temple still writes the platform audit log, bans and notices included")
	void platformAuditOperatorWritesAreUnchanged() {
		// Operators have tenant_id NULL, so they can never satisfy V133's temple condition; they write
		// through V9's platform_audit_superadmin_insert, which V133 does not touch and which does not
		// look at entity_type. The notice is how operators actually write today (raise and withdraw with
		// no temple); USER is the account claim. Operators hold no MANAGE_STAFF, so they do not write the
		// ban types in practice — included to show V9 would admit them regardless, i.e. nothing about
		// the operator path hangs on the escapes V133 narrowed.
		UUID operator = insertMember(null, "uid-audit-operator", "+919800000044", "SUPER_ADMIN", "ACTIVE");
		try {
			TenantContext.setAuthLookupUid("uid-audit-operator");
			assertThat(TenantContext.get()).as("no temple is set for an operator").isEmpty();

			for (String entityType : List.of("USER", "PLATFORM_NOTICE", "EMPLOYMENT_BAN_CHECK")) {
				assertThat(insertPlatformAudit(operator, entityType))
						.as("an operator's %s row is admitted by V9's policy", entityType)
						.isEqualTo(1);
			}
			assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_audit_events", Integer.class))
					.as("and the operator reads them back").isEqualTo(3);
		} finally {
			TenantContext.clear();
			admin.update("DELETE FROM platform_audit_events");
			admin.update("DELETE FROM users WHERE id = ?", operator);
		}
	}

	@Test
	@DisplayName("a temple user who may write the platform audit log still cannot read it")
	void platformAuditStaysUnreadableToATempleUser() {
		UUID atA = insertMember(templeA, "uid-audit-reader", "+919800000045", "TEMPLE_ADMIN", "ACTIVE");
		try {
			TenantContext.set(templeA);
			TenantContext.setAuthLookupUid("uid-audit-reader");
			assertThat(insertPlatformAudit(atA, "PLATFORM_NOTICE")).isEqualTo(1);

			assertThat(jdbc.queryForObject("SELECT count(*) FROM platform_audit_events", Integer.class))
					.as("the writer cannot read even the row they just wrote").isZero();
			assertThat(admin.queryForObject("SELECT count(*) FROM platform_audit_events", Integer.class))
					.as("though it is there").isEqualTo(1);
		} finally {
			TenantContext.clear();
			admin.update("DELETE FROM platform_audit_events");
			admin.update("DELETE FROM users WHERE id = ?", atA);
		}
	}

	// ---------------------------------------------------------------------
	// T-193 (V134): an operator's platform audit row must name the operator who wrote it.
	//
	// V9's platform_audit_superadmin_insert asked only whether the connected uid held a SUPER_ADMIN
	// row, never whether actor_user_id was that row. These cases pin the author. Each method makes
	// exactly one assertion, so each refusal can go red on its own in the negative control: with V134
	// removed the two "as themselves" cases stay green and every mis-authored case goes red.
	//
	// Every case is an operator request as the authentication filter leaves one: app.auth_uid set to
	// the operator's verified uid, no temple set (the filter sets a tenant only for a temple account).
	//
	// The refusal cases run as TENANT, which only operators write, and as PLATFORM_NOTICE, which V133's
	// notice escape also covers. Permissive policies are OR'd, so the second proves that escape does
	// not readmit a mis-authored operator row (it needs app.tenant_id, which an operator never has).
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("an operator authoring a platform notice row as themselves is admitted")
	void platformAuditOperatorAsThemselvesWritesANotice() {
		// The real path: NoticeService.raise and withdraw, called by an operator from NoticeController.
		UUID operator = insertMember(null, "uid-t193-operator", "+919800000051", "SUPER_ADMIN", "ACTIVE");
		try {
			TenantContext.setAuthLookupUid("uid-t193-operator");

			assertThat(insertPlatformAudit(operator, "PLATFORM_NOTICE"))
					.as("an operator's own PLATFORM_NOTICE row is the legitimate write")
					.isEqualTo(1);
		} finally {
			TenantContext.clear();
			admin.update("DELETE FROM platform_audit_events");
			admin.update("DELETE FROM users WHERE id = ?", operator);
		}
	}

	@Test
	@DisplayName("an operator authoring a temple export or deletion row as themselves is admitted")
	void platformAuditOperatorAsThemselvesRecordsATempleAction() {
		// Operators write no ban rows today: every ban write sits behind MANAGE_STAFF, which a
		// SUPER_ADMIN does not hold (T-192). The operator-only entity type written today is TENANT —
		// TenantExportService.export (TENANT_EXPORTED) and TenantDeletionService.delete
		// (TENANT_DELETED), both from TenantController behind DELETE_TENANT. Neither V65's nor V66's
		// escape covers TENANT, so this row is admitted by V134's policy alone.
		UUID operator = insertMember(null, "uid-t193-operator", "+919800000051", "SUPER_ADMIN", "ACTIVE");
		try {
			TenantContext.setAuthLookupUid("uid-t193-operator");

			assertThat(insertPlatformAudit(operator, "TENANT"))
					.as("an operator's own TENANT row is the legitimate write")
					.isEqualTo(1);
		} finally {
			TenantContext.clear();
			admin.update("DELETE FROM platform_audit_events");
			admin.update("DELETE FROM users WHERE id = ?", operator);
		}
	}

	@ParameterizedTest(name = "operator names another operator, as {0}")
	@ValueSource(strings = {"TENANT", "PLATFORM_NOTICE"})
	@DisplayName("an operator cannot author a platform audit row as another operator")
	void platformAuditOperatorCannotNameAnotherOperator(String entityType) {
		UUID operator = insertMember(null, "uid-t193-operator", "+919800000051", "SUPER_ADMIN", "ACTIVE");
		UUID otherOperator = insertMember(null, "uid-t193-other-operator", "+919800000052", "SUPER_ADMIN", "ACTIVE");
		try {
			TenantContext.setAuthLookupUid("uid-t193-operator");

			assertThatThrownBy(() -> insertPlatformAudit(otherOperator, entityType))
					.as("a %s row naming a different Super Admin as its author must be refused", entityType)
					.hasStackTraceContaining("row-level security");
		} finally {
			TenantContext.clear();
			admin.update("DELETE FROM platform_audit_events");
			admin.update("DELETE FROM users WHERE id IN (?, ?)", operator, otherOperator);
		}
	}

	@ParameterizedTest(name = "operator names a temple user, as {0}")
	@ValueSource(strings = {"TENANT", "PLATFORM_NOTICE"})
	@DisplayName("an operator cannot author a platform audit row as a temple user")
	void platformAuditOperatorCannotNameATempleUser(String entityType) {
		UUID operator = insertMember(null, "uid-t193-operator", "+919800000051", "SUPER_ADMIN", "ACTIVE");
		UUID templeAdmin = insertMember(templeA, "uid-t193-temple-admin", "+919800000053", "TEMPLE_ADMIN", "ACTIVE");
		try {
			TenantContext.setAuthLookupUid("uid-t193-operator");

			assertThatThrownBy(() -> insertPlatformAudit(templeAdmin, entityType))
					.as("a %s row naming somebody's temple account as its author must be refused", entityType)
					.hasStackTraceContaining("row-level security");
		} finally {
			TenantContext.clear();
			admin.update("DELETE FROM platform_audit_events");
			admin.update("DELETE FROM users WHERE id IN (?, ?)", operator, templeAdmin);
		}
	}

	@ParameterizedTest(name = "operator names their own temple account, as {0}")
	@ValueSource(strings = {"TENANT", "PLATFORM_NOTICE"})
	@DisplayName("an operator cannot author a platform audit row as their own account at a temple")
	void platformAuditOperatorCannotNameTheirOwnTempleAccount(String entityType) {
		// The sharper form of the case above. Since V52 one uid may hold a temple account as well as
		// the operator row, and that account carries the same verified uid and is visible to the
		// request (V2, V4). So matching the author on the uid alone would admit it; the policy has to
		// name the SUPER_ADMIN row itself.
		UUID operator = insertMember(null, "uid-t193-operator", "+919800000051", "SUPER_ADMIN", "ACTIVE");
		UUID ownTempleAccount = insertMember(templeA, "uid-t193-operator", "+919800000051", "TEMPLE_ADMIN", "ACTIVE");
		try {
			TenantContext.setAuthLookupUid("uid-t193-operator");

			assertThatThrownBy(() -> insertPlatformAudit(ownTempleAccount, entityType))
					.as("a %s row naming the operator's own temple account as its author must be refused", entityType)
					.hasStackTraceContaining("row-level security");
		} finally {
			TenantContext.clear();
			admin.update("DELETE FROM platform_audit_events");
			admin.update("DELETE FROM users WHERE id IN (?, ?)", operator, ownTempleAccount);
		}
	}

	@Test
	@DisplayName("an operator cannot author a platform audit row with no author at all")
	void platformAuditOperatorCannotNameNobody() {
		// actor_user_id has been nullable since V66 (so a deleted temple's authors can go to NULL
		// rather than block the purge), and a NULL satisfies no foreign key. Before V134 nothing
		// refused an operator writing an authorless row; now u.id = NULL matches no row.
		UUID operator = insertMember(null, "uid-t193-operator", "+919800000051", "SUPER_ADMIN", "ACTIVE");
		try {
			TenantContext.setAuthLookupUid("uid-t193-operator");

			assertThatThrownBy(() -> insertPlatformAudit(null, "TENANT"))
					.as("a TENANT row with a NULL author must be refused")
					.hasStackTraceContaining("row-level security");
		} finally {
			TenantContext.clear();
			admin.update("DELETE FROM platform_audit_events");
			admin.update("DELETE FROM users WHERE id = ?", operator);
		}
	}

	@Test
	@DisplayName("an operator naming an id that is nobody is refused by the policy, not only by the foreign key")
	void platformAuditOperatorCannotNameAnIdThatIsNobody() {
		// Before V134 this was already refused, by the foreign key platform_audit_events_actor (V66).
		// The assertion is on the refusal's cause: PostgreSQL checks the row-level WITH CHECK before
		// the foreign key (an after-row trigger), so under V134 the policy refuses it first. Without
		// V134 this goes red with a foreign-key message rather than being admitted — which shows the
		// FK was the only thing standing there, and that it would not have been for a NULL (above).
		UUID operator = insertMember(null, "uid-t193-operator", "+919800000051", "SUPER_ADMIN", "ACTIVE");
		try {
			TenantContext.setAuthLookupUid("uid-t193-operator");

			assertThatThrownBy(() -> insertPlatformAudit(UUID.randomUUID(), "TENANT"))
					.as("a TENANT row naming no user must be refused by row-level security")
					.hasStackTraceContaining("row-level security");
		} finally {
			TenantContext.clear();
			admin.update("DELETE FROM platform_audit_events");
			admin.update("DELETE FROM users WHERE id = ?", operator);
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
		return jdbc.queryForList("SELECT name FROM rls_fixture", String.class);
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

	private UUID insertPerson(UUID tenantId, String uid, String phone) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Person', ?, ?, 'VOLUNTEER', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenantId, uid, uid + "@example.com", phone);
	}

	/** Seeded through the privileged connection; a null tenant is an operator. */
	private UUID insertMember(UUID tenantId, String uid, String phone, String role, String status) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Person', ?, ?, ?, ?)
				RETURNING id
				""", UUID.class, tenantId, uid, uid + "@example.com", phone, role, status);
	}

	/** Through the application's own connection, so every policy applies. */
	private int insertPlatformAudit(UUID actorUserId, String entityType) {
		return jdbc.update("""
				INSERT INTO platform_audit_events (actor_user_id, actor_label, action, entity_type, entity_id)
				VALUES (?, 'Person', 'TEST_WRITE', ?, ?)
				""", actorUserId, entityType, UUID.randomUUID());
	}

	private void seedRecipe(UUID tenantId, String name) {
		admin.update("INSERT INTO rls_fixture (tenant_id, name) VALUES (?, ?)", tenantId, name);
	}
}
