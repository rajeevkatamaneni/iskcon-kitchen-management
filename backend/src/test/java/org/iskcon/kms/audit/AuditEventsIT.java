package org.iskcon.kms.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the two database-enforced properties of the audit log: it is append-only, and it is
 * tenant-isolated.
 *
 * <p>The distinction between the two matters and is why the append-only tests set a tenant context
 * before attempting a write. Without a context, RLS alone would make an UPDATE match zero rows —
 * which looks like append-only but is not it. Setting the row's own tenant makes RLS <em>permit</em>
 * the write, so what stops it can only be the missing privilege. That is the guarantee this story
 * adds on top of RLS, and it is tested the same way {@link org.iskcon.kms.tenancy.RowLevelSecurityIT}
 * tests isolation — as real database behaviour, through the unprivileged application role.
 */
class AuditEventsIT extends AbstractIntegrationTest {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private AuditQueryService auditQueryService;

	private JdbcTemplate jdbc;
	private JdbcTemplate admin;

	private UUID templeA;
	private UUID templeB;
	private UUID actorA;
	private UUID actorB;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		jdbc = new JdbcTemplate(dataSource);
		admin = new JdbcTemplate(adminDataSource());

		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");
		actorA = insertUser(templeA, "uid-a", "admin-a@example.com");
		actorB = insertUser(templeB, "uid-b", "admin-b@example.com");

		seedEvent(templeA, actorA, AuditAction.ROLE_CHANGED);
		seedEvent(templeA, actorA, AuditAction.ROLE_CHANGE_REJECTED);
		seedEvent(templeB, actorB, AuditAction.ROLE_CHANGED);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		// As the privileged role, which append-only does not bind — clearing the trail first so
		// the RESTRICT foreign keys to users and tenants allow those deletes.
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a tenant sees only its own audit events")
	void tenantSeesOnlyOwnEvents() {
		TenantContext.set(templeA);
		assertThat(countVisible()).isEqualTo(2);

		TenantContext.set(templeB);
		assertThat(countVisible()).isEqualTo(1);
	}

	@Test
	@DisplayName("no tenant context means no audit events, not all of them")
	void withoutContextNothingIsVisible() {
		TenantContext.clear();
		assertThat(countVisible())
				.as("an unconfigured connection must fail closed, quietly")
				.isZero();
	}

	@Test
	@DisplayName("the application role cannot UPDATE an audit row, even one it is allowed to see")
	void appRoleCannotUpdate() {
		TenantContext.set(templeA);

		assertThatThrownBy(() ->
				jdbc.update("UPDATE audit_events SET reason = 'tampered' WHERE tenant_id = ?", templeA))
				.as("append-only: UPDATE must be refused by a missing privilege, not by RLS")
				.isInstanceOf(DataAccessException.class);

		Integer tampered = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE reason = 'tampered'", Integer.class);
		assertThat(tampered).as("nothing was actually changed").isZero();
	}

	@Test
	@DisplayName("the application role cannot DELETE an audit row, even one it is allowed to see")
	void appRoleCannotDelete() {
		TenantContext.set(templeA);

		assertThatThrownBy(() ->
				jdbc.update("DELETE FROM audit_events WHERE tenant_id = ?", templeA))
				.as("append-only: DELETE must be refused by a missing privilege")
				.isInstanceOf(DataAccessException.class);

		Integer remaining = admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE tenant_id = ?", Integer.class, templeA);
		assertThat(remaining).as("both of temple A's events remain").isEqualTo(2);
	}

	@Test
	@DisplayName("append-only did not over-revoke: the application role can still INSERT")
	void appRoleCanStillInsert() {
		// The write path (the kernel) depends on INSERT surviving the revoke of UPDATE/DELETE.
		TenantContext.set(templeA);

		int inserted = jdbc.update("""
				INSERT INTO audit_events (tenant_id, actor_user_id, actor_label, action,
						entity_type, entity_id)
				VALUES (?, ?, 'App Actor', 'ROLE_CHANGED', 'USER', gen_random_uuid())
				""", templeA, actorA);

		assertThat(inserted).isEqualTo(1);
		assertThat(countVisible()).isEqualTo(3);
	}

	@Test
	@DisplayName("the viewer paginates 10k events by keyset, returning every one exactly once in order")
	void paginatesLargeVolumeWithoutLoss() {
		// The story's scale criterion. A separate temple so the count is exactly what is seeded.
		UUID templeC = insertTenant("radha-madhava", "Sri Sri Radha Madhava Temple");
		UUID actorC = insertUser(templeC, "uid-c", "admin-c@example.com");

		admin.update("""
				INSERT INTO audit_events (tenant_id, actor_user_id, actor_label, action,
						entity_type, entity_id, created_at)
				SELECT ?, ?, 'Seed', 'ROLE_CHANGED', 'USER', gen_random_uuid(),
					   now() - (g * interval '1 second')
				FROM generate_series(1, 10000) AS g
				""", templeC, actorC);

		TenantContext.set(templeC);

		int seen = 0;
		String cursor = null;
		Instant previousTime = null;

		do {
			AuditQuery query = new AuditQuery(
					null, null, null, null,
					cursor == null ? null : AuditCursor.decode(cursor),
					500);
			AuditPage page = auditQueryService.forCurrentTenant(query);

			for (AuditEventView event : page.events()) {
				if (previousTime != null) {
					assertThat(event.createdAt())
							.as("events must arrive strictly newest-first, with no repeats across pages")
							.isBefore(previousTime);
				}
				previousTime = event.createdAt();
				seen++;
			}
			cursor = page.nextCursor();
		} while (cursor != null);

		assertThat(seen)
				.as("every seeded event is returned exactly once — none lost, none duplicated")
				.isEqualTo(10000);
	}

	// ---------------------------------------------------------------------

	private int countVisible() {
		Integer count = jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class);
		return count == null ? 0 : count;
	}

	private UUID insertTenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);
	}

	private UUID insertUser(UUID tenantId, String uid, String email) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Admin', ?, '+919000000001', 'TEMPLE_ADMIN', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenantId, uid, email);
	}

	private void seedEvent(UUID tenantId, UUID actorId, AuditAction action) {
		admin.update("""
				INSERT INTO audit_events (tenant_id, actor_user_id, actor_label, action,
						entity_type, entity_id)
				VALUES (?, ?, 'Seed Actor', ?, 'USER', gen_random_uuid())
				""", tenantId, actorId, action.name());
	}
}
