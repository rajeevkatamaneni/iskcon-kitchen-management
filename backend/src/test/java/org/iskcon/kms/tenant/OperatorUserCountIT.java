package org.iskcon.kms.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.TokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The operator's temple list and temple page say how many people hold an account at each temple —
 * and until T-061 they said 0, for every temple, always, without erroring.
 *
 * <p>The cause was Row-Level Security working correctly. {@code users} is tenant-owned and carries
 * {@code FORCE ROW LEVEL SECURITY}; a platform operator is tenantless; so the policy's
 * {@code tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid} compared against a
 * NULL, matched nothing, and {@code count(*)} over nothing is a perfectly ordinary 0. An aggregate
 * degrades to a plausible wrong number where a row query would have degraded to an empty screen
 * somebody would have reported years earlier, which is why this survived so long.
 *
 * <p>This class therefore tests three separate things, and the second is the one worth keeping:
 *
 * <ol>
 *   <li><strong>The fix.</strong> A temple with three people reads three, a temple with none reads
 *       zero, on both the list and the detail page.
 *   <li><strong>The explanation.</strong> That a plain {@code SECURITY DEFINER} function — the
 *       obvious reach, and what this task originally called for — would <em>not</em> have fixed it,
 *       because {@code FORCE ROW LEVEL SECURITY} subjects the table's own owner to the policy. That
 *       claim is checked against the database rather than argued: the naive function is built here,
 *       as the schema-owning migration role, and watched returning 0.
 *   <li><strong>The cost of the fix.</strong> That borrowing a temple's context to count does not
 *       leave the caller holding it. The function is called once per row of the operator's list, so
 *       a context left behind would silently hand the rest of that request one temple's visibility.
 * </ol>
 *
 * <p>Everything below the MockMvc tests goes through a raw {@code kms_app} connection with
 * autocommit off. Both details matter. As {@code kms_app} the policies genuinely apply — the
 * container superuser bypasses RLS outright and would prove nothing. And inside an explicit
 * transaction a transaction-local setting that was never restored is still visible; under
 * autocommit it would evaporate at the end of the statement and a broken restore would pass.
 */
@AutoConfigureMockMvc
@Import(OperatorUserCountIT.StubVerifierConfiguration.class)
class OperatorUserCountIT extends AbstractIntegrationTest {

	/** The naive function of test 2. Namespaced and dropped, since the container is shared. */
	private static final String NAIVE_FUNCTION = "t061_naive_user_count";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private ObjectMapper json;

	private JdbcTemplate admin;

	/** A temple with three people in it, and one with none. */
	private UUID busy;
	private UUID empty;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		stubVerifier.reset();
		clearSeed();

		busy = seedTemple("radha-govinda-t061", "Sri Sri Radha Govinda Temple");
		empty = seedTemple("krishna-balaram-t061", "Sri Krishna Balaram Temple");

		// Exactly the three that were on staging when this was measured: one of each kind of
		// account a temple actually issues.
		seedUser(busy, "TEMPLE_ADMIN", "admin");
		seedUser(busy, "KITCHEN_STAFF", "cook");
		seedUser(busy, "VOLUNTEER", "volunteer");
	}

	@AfterEach
	void tearDown() {
		clearSeed();
		dropNaiveFunction();
	}

	// ---------------------------------------------------------------------
	// 1. The fix.
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("the operator's temple list shows the true headcount, and 0 only where it is true")
	void listShowsTheRealHeadcount() throws Exception {
		signInAsSuperAdmin();

		String body = mvc.perform(authed(get("/api/v1/tenants")))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(userCountOf(body, "radha-govinda-t061")).isEqualTo(3);

		// The point of seeding a second temple: 0 must still be reachable, or a fix that hardcoded
		// a non-zero number would pass. A temple genuinely nobody has an account at reads 0.
		assertThat(userCountOf(body, "krishna-balaram-t061")).isZero();
	}

	@Test
	@DisplayName("one temple's page shows the true headcount too — both readers were wrong, not one")
	void detailShowsTheRealHeadcount() throws Exception {
		signInAsSuperAdmin();

		mvc.perform(authed(get("/api/v1/tenants/{id}", busy)))
				.andExpect(status().isOk())
				// last_export_at, on the same row, never had this defect and is left alone: it reads
				// platform_audit_events, which is a platform table with no tenant_id and a policy
				// that admits a verified super-admin directly. That contrast is the whole rule — an
				// operator reading a table with a tenant_id column needs a tenant context.
				.andExpect(jsonPath("$.user_count").value(3));

		mvc.perform(authed(get("/api/v1/tenants/{id}", empty)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.user_count").value(0));
	}

	@Test
	@DisplayName("the count is an operator's to see and nobody else's — a temple admin is still refused")
	void aTempleAdminStillCannotReachTheOperatorScreens() throws Exception {
		// Signed in, real account, and it changes nothing: the endpoints are behind MANAGE_TENANTS
		// and stay there. The fix widened what the operator can count, not who may ask.
		stubVerifier.accept("uid-t061-admin-" + busy);

		mvc.perform(authed(get("/api/v1/tenants"))).andExpect(status().isForbidden());
		mvc.perform(authed(get("/api/v1/tenants/{id}", busy))).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------------
	// 2. The explanation: why SECURITY DEFINER alone is not the fix.
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("a plain SECURITY DEFINER count still answers 0 — FORCE RLS binds the owner too")
	void securityDefinerAloneDoesNotEscapeTheOwnPolicy() throws Exception {
		createNaiveFunctionAsSchemaOwner();

		try (Connection app = appConnection()) {
			app.setAutoCommit(false);
			// The operator's session, exactly as TenantAwareDataSource leaves it: the empty string,
			// which is what "no tenant" is written as everywhere in this system.
			setSetting(app, "app.tenant_id", "");

			// The claim under test. The function is SECURITY DEFINER and owned by kms_migrator,
			// which owns `users` — and it still sees nothing, because FORCE ROW LEVEL SECURITY
			// exists precisely to subject an owner to its own policy. If this ever comes back 3,
			// the simpler fix is available and V102 should be reduced to it.
			assertThat(callFunction(app, NAIVE_FUNCTION, busy)).isZero();

			// Same session, same connection, same role, same instant: the scoped function answers
			// correctly. The difference between the two is the set_config, and nothing else.
			assertThat(callFunction(app, "tenant_user_count", busy)).isEqualTo(3);

			app.rollback();
		}
	}

	// ---------------------------------------------------------------------
	// 3. The cost of the fix: the caller's context survives the count.
	// ---------------------------------------------------------------------

	@Test
	@DisplayName("counting leaves a tenantless operator seeing nothing, as they were")
	void theOperatorsEmptyContextIsPutBack() throws Exception {
		try (Connection app = appConnection()) {
			app.setAutoCommit(false);
			setSetting(app, "app.tenant_id", "");

			assertThat(callFunction(app, "tenant_user_count", busy)).isEqualTo(3);

			// Restored as '' rather than as NULL: the empty string is what this system writes for
			// "no tenant", and the policies read it through NULLIF(..., ''). Asserting the exact
			// value rather than just "not the temple" is deliberate — a restore to NULL would
			// behave the same today and would be the one place in the system that says it
			// differently.
			assertThat(setting(app, "app.tenant_id")).isEqualTo("");

			// And what actually matters: the next query in the operator's request still sees no
			// users at all. Without the restore this reads 3 — the operator would be walking
			// around inside Radha Govinda's data for the rest of the transaction.
			assertThat(visibleUsers(app)).isZero();

			app.rollback();
		}
	}

	@Test
	@DisplayName("counting another temple does not move a tenant user into it")
	void aCallersOwnTenantContextIsPutBack() throws Exception {
		seedUser(empty, "TEMPLE_ADMIN", "other-admin");

		try (Connection app = appConnection()) {
			app.setAutoCommit(false);
			setSetting(app, "app.tenant_id", empty.toString());

			// One person at their own temple, before.
			assertThat(visibleUsers(app)).isEqualTo(1);

			assertThat(callFunction(app, "tenant_user_count", busy)).isEqualTo(3);

			// The setting is theirs again — asserted inside the transaction, because a
			// transaction-local setting reverts at commit and a test that looked afterwards would
			// pass whether or not the function restored anything.
			assertThat(setting(app, "app.tenant_id")).isEqualTo(empty.toString());

			// One person, still. Not four, and not three.
			assertThat(visibleUsers(app)).isEqualTo(1);

			app.rollback();
		}
	}

	@Test
	@DisplayName("the count is confined to the one temple asked for — it is never a way to read across")
	void theCountAnswersForOneTempleOnly() throws Exception {
		try (Connection app = appConnection()) {
			app.setAutoCommit(false);
			setSetting(app, "app.tenant_id", "");

			assertThat(callFunction(app, "tenant_user_count", busy)).isEqualTo(3);
			assertThat(callFunction(app, "tenant_user_count", empty)).isZero();

			// A temple that does not exist is not an error and not a leak: nobody holds an account
			// at it. The function has no other answer available to it than a number.
			assertThat(callFunction(app, "tenant_user_count", UUID.randomUUID())).isZero();

			app.rollback();
		}
	}

	@Test
	@DisplayName("the function pins its search_path and returns a count, not rows")
	void theFunctionIsShapedAsASecurityDefinerFunctionMustBe() {
		Map<String, Object> definition = admin.queryForMap("""
				SELECT p.prosecdef                            AS security_definer,
				       array_to_string(p.proconfig, ' | ')    AS config,
				       t.typname                              AS return_type,
				       p.proretset                            AS returns_a_set
				FROM pg_proc p
				JOIN pg_type t ON t.oid = p.prorettype
				WHERE p.proname = 'tenant_user_count'
				""");

		assertThat(definition.get("security_definer")).isEqualTo(true);

		// Unpinned, the caller chooses which schema `users` resolves to and the function then reads
		// their table with the owner's rights. This is not a style rule.
		assertThat(definition.get("config")).isEqualTo("search_path=pg_catalog, public");

		// A count and nothing else — no rows, no set, no personal data. This is what puts the
		// function on the permitted side of D-13 by construction rather than by promise.
		assertThat(definition.get("return_type")).isEqualTo("int8");
		assertThat(definition.get("returns_a_set")).isEqualTo(false);
	}

	// ---------------------------------------------------------------------
	// Raw connections, as the two unprivileged roles production actually uses.
	// ---------------------------------------------------------------------

	/** The application role: no DDL, no BYPASSRLS, so every policy genuinely binds. */
	private static Connection appConnection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
	}

	/** The schema-owning migration role — what a SECURITY DEFINER function here runs as. */
	private static Connection migratorConnection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), MIGRATION_ROLE, MIGRATION_PASSWORD);
	}

	/**
	 * The function this task was originally asked for: SECURITY DEFINER, pinned search_path, and no
	 * borrowed tenant context. Created by the schema owner so it is owned by the schema owner —
	 * created by the container superuser it would run as a superuser, bypass RLS outright, and
	 * appear to work for a reason that does not exist in production.
	 */
	private void createNaiveFunctionAsSchemaOwner() throws SQLException {
		try (Connection migrator = migratorConnection();
				Statement statement = migrator.createStatement()) {
			statement.execute("""
					CREATE OR REPLACE FUNCTION %s(p_tenant uuid)
					RETURNS bigint
					LANGUAGE plpgsql
					SECURITY DEFINER
					SET search_path = pg_catalog, public
					AS $naive$
					BEGIN
					    RETURN (SELECT count(*) FROM public.users u WHERE u.tenant_id = p_tenant);
					END;
					$naive$;
					""".formatted(NAIVE_FUNCTION));
			statement.execute("GRANT EXECUTE ON FUNCTION %s(uuid) TO %s".formatted(NAIVE_FUNCTION, APP_ROLE));
		}
	}

	private void dropNaiveFunction() {
		try (Connection migrator = migratorConnection();
				Statement statement = migrator.createStatement()) {
			statement.execute("DROP FUNCTION IF EXISTS %s(uuid)".formatted(NAIVE_FUNCTION));
		} catch (SQLException e) {
			throw new IllegalStateException("Could not drop the control function", e);
		}
	}

	private static long callFunction(Connection connection, String function, UUID tenantId)
			throws SQLException {
		try (PreparedStatement ps =
				connection.prepareStatement("SELECT " + function + "(?::uuid)")) {
			ps.setString(1, tenantId.toString());
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getLong(1);
			}
		}
	}

	/** Set at session scope with bound parameters, exactly as TenantAwareDataSource does it. */
	private static void setSetting(Connection connection, String key, String value)
			throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("SELECT set_config(?, ?, false)")) {
			ps.setString(1, key);
			ps.setString(2, value);
			ps.execute();
		}
	}

	private static String setting(Connection connection, String key) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("SELECT current_setting(?, true)")) {
			ps.setString(1, key);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getString(1);
			}
		}
	}

	/** Every users row this connection can see right now — the policy's own verdict. */
	private static long visibleUsers(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery("SELECT count(*) FROM users")) {
			rs.next();
			return rs.getLong(1);
		}
	}

	// ---------------------------------------------------------------------
	// Fixtures. Seeded as the superuser, which is the one thing a superuser is for here.
	// ---------------------------------------------------------------------

	private UUID seedTemple(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);
	}

	private void seedUser(UUID tenantId, String role, String who) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
				""",
				tenantId,
				"uid-t061-" + who + "-" + tenantId,
				"T061 " + who,
				who + "." + tenantId + "@example.com",
				phoneFor(who),
				role);
	}

	// E.164, and distinct per account so the seed does not collide with itself.
	private static String phoneFor(String who) {
		return "+9198765" + String.format("%05d", Math.abs(who.hashCode()) % 100000);
	}

	private void clearSeed() {
		admin.update("DELETE FROM users WHERE firebase_uid LIKE 'uid-t061-%'");
		admin.update("DELETE FROM tenants WHERE slug LIKE '%-t061'");
	}

	private void signInAsSuperAdmin() {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, 'uid-t061-super', 'Platform Operator', 't061.super@example.com',
						'+919000000061', 'SUPER_ADMIN', 'ACTIVE')
				""");
		stubVerifier.accept("uid-t061-super");
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer valid-token");
	}

	/**
	 * The list is every temple in the database, and other classes leave their own behind, so the
	 * assertion finds this test's temple by slug rather than trusting a position.
	 */
	private long userCountOf(String body, String slug) throws Exception {
		List<Map<String, Object>> rows = json.readValue(body, new TypeReference<>() {});
		return rows.stream()
				.filter(row -> slug.equals(row.get("slug")))
				.findFirst()
				.map(row -> ((Number) row.get("user_count")).longValue())
				.orElseThrow(() -> new AssertionError("The temple list did not contain " + slug));
	}

	// ---------------------------------------------------------------------

	@TestConfiguration
	static class StubVerifierConfiguration {

		@Bean
		@Primary
		StubTokenVerifier stubTokenVerifier() {
			return new StubTokenVerifier();
		}
	}

	static class StubTokenVerifier implements TokenVerifier {

		private final Map<String, VerifiedSubject> accepted = new HashMap<>();

		void accept(String uid) {
			accepted.put("valid-token", new VerifiedSubject(uid, uid + "@example.com", "+919000000000"));
		}

		void reset() {
			accepted.clear();
		}

		@Override
		public VerifiedSubject verify(String idToken) throws InvalidTokenException {
			VerifiedSubject subject = accepted.get(idToken);
			if (subject == null) {
				throw new InvalidTokenException("Unrecognised token");
			}
			return subject;
		}
	}
}
