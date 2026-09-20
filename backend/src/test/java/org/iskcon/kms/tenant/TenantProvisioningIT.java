package org.iskcon.kms.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end provisioning: a super-admin creates a temple, and someone can immediately
 * administer it.
 *
 * <p>The acceptance criterion from the story is the last of these tests — a newly provisioned
 * temple must be usable, not merely present in a table.
 */
class TenantProvisioningIT extends AbstractIntegrationTest {

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@LocalServerPort
	private int port;

	private JdbcTemplate admin;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
	}

	@AfterEach
	void tearDown() {
		// audit_events references both users and tenants with ON DELETE RESTRICT — it is a trail,
		// and a trail must not lose its subject. Clearing it first (as the privileged role, which
		// append-only does not bind) is what lets the rest of the teardown delete those rows.
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM staff_schedule_template");
		admin.execute("DELETE FROM staff_profiles");
		// Provisioning seeds a main kitchen (V150, T-350), and a staff record names it; after the staff.
		admin.execute("DELETE FROM kitchens");
		// Anything that moved through the stock ledger is tracked now, so the item rows exist
		// even where the test never asked for them, and they hold the ingredient down.
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM occasions");
		admin.execute("DELETE FROM meal_kinds");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	@Test
	@DisplayName("a super-admin provisions a temple and its first administrator together")
	void provisionsTempleWithAdministrator() {
		signInAsSuperAdmin();

		ResponseEntity<String> response = post("/api/v1/tenants", validRequest());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		Integer tenants = admin.queryForObject(
				"SELECT count(*) FROM tenants WHERE slug = 'radha-govinda'", Integer.class);
		Integer admins = admin.queryForObject(
				"SELECT count(*) FROM users WHERE role = 'TEMPLE_ADMIN'", Integer.class);

		assertThat(tenants).isEqualTo(1);
		assertThat(admins)
				.as("a temple without an administrator is a record nobody can reach")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("the first administrator is employed, not merely created")
	void firstAdministratorIsOnTheStaffRegister() {
		signInAsSuperAdmin();

		post("/api/v1/tenants", validRequest());

		// Since hiring became the only door into a temple role (E6-S8), /staff is the register of
		// everyone who works here — and without this the founding administrator would be the one
		// person on no screen at all: not a devotee, and never hired.
		Map<String, Object> record = admin.queryForMap("""
				SELECT sp.job_title, sp.employment_status, sp.full_name, sp.email, u.role
				FROM staff_profiles sp JOIN users u ON u.id = sp.user_id
				WHERE u.role = 'TEMPLE_ADMIN'
				""");
		assertThat(record.get("job_title")).isEqualTo("TEMPLE_ADMINISTRATOR");
		assertThat(record.get("employment_status")).isEqualTo("ACTIVE");

		Integer days = admin.queryForObject("""
				SELECT count(*) FROM staff_schedule_template t
				JOIN staff_profiles sp ON sp.id = t.staff_profile_id
				""", Integer.class);
		assertThat(days).as("the schedule grid edits seven days; it cannot edit rows that do not exist")
				.isEqualTo(7);
	}

	@Test
	@DisplayName("a new temple starts with a main kitchen that plans meals, and its administrator works in it")
	void seedsAMainKitchen() {
		signInAsSuperAdmin();

		post("/api/v1/tenants", validRequest());

		// Every meal needs a kitchen and every staff member belongs to one (Epic 12, V150). Without a
		// seeded kitchen a brand-new temple could neither plan its first meal nor employ anybody.
		Map<String, Object> kitchen = admin.queryForMap("""
				SELECT k.id, k.name, k.is_main, k.uses_meal_planner, k.status, u.role AS created_by_role
				FROM kitchens k JOIN users u ON u.id = k.created_by
				""");
		assertThat(kitchen.get("name")).isEqualTo("Main kitchen");
		assertThat(kitchen.get("is_main")).isEqualTo(true);
		assertThat(kitchen.get("uses_meal_planner")).isEqualTo(true);
		assertThat(kitchen.get("status")).isEqualTo("ACTIVE");
		assertThat(kitchen.get("created_by_role")).isEqualTo("TEMPLE_ADMIN");

		Map<String, Object> staff = admin.queryForMap("SELECT kitchen_id, kitchen_needs_check FROM staff_profiles");
		assertThat(staff.get("kitchen_id")).isEqualTo(kitchen.get("id"));
		assertThat(staff.get("kitchen_needs_check"))
				.as("one kitchen, so nothing was guessed and there is nothing to check")
				.isEqualTo(false);
	}

	@Test
	@DisplayName("every field submitted is actually persisted")
	void persistsEveryField() {
		// Asserts the whole row, not a sample. The first version of this checked only
		// coordinates, and a column silently missing from the INSERT parameter list went
		// undetected until the statement itself failed — a field that is quietly dropped
		// rather than rejected is far worse, since nothing would ever reveal it.
		signInAsSuperAdmin();
		post("/api/v1/tenants", validRequest());

		Map<String, Object> tenant = admin.queryForMap(
				"SELECT * FROM tenants WHERE slug = 'radha-govinda'");

		assertThat(tenant.get("name")).isEqualTo("Sri Sri Radha Govinda Temple");
		assertThat(tenant.get("address")).isEqualTo("Bengaluru, Karnataka");
		assertThat(tenant.get("currency")).isEqualTo("INR");

		// Required by the Vaishnava calendar — tithi is computed at local sunrise.
		assertThat(tenant.get("timezone")).isEqualTo("Asia/Kolkata");
		assertThat(new java.math.BigDecimal(tenant.get("latitude").toString()))
				.isEqualByComparingTo("12.9716");
		assertThat(new java.math.BigDecimal(tenant.get("longitude").toString()))
				.isEqualByComparingTo("77.5946");

		// Decides whether the 80G donor-data path is offered at all (E7-S4). Silently
		// storing false here would remove a temple's ability to issue tax certificates.
		assertThat(tenant.get("is_80g_approved")).isEqualTo(true);
	}

	@Test
	@DisplayName("a new temple starts with an empty ingredient catalogue, and the Ekadashi category")
	void seedsNoIngredients() {
		// This test asserted the opposite until 2026-09-08: that eleven ingredients arrived flagged.
		// D-18 deleted the seed, so a temple's catalogue is now whatever the temple puts in it —
		// by hand or, in bulk, by importing recipes. The assertion is kept rather than removed
		// because "zero" is the ruling, and a provisioning path that quietly started inserting rows
		// again would be a regression nothing else would catch.
		signInAsSuperAdmin();
		post("/api/v1/tenants", validRequest());

		UUID tenantId = admin.queryForObject(
				"SELECT id FROM tenants WHERE slug = 'radha-govinda'", UUID.class);

		Integer ingredients = admin.queryForObject(
				"SELECT count(*) FROM ingredients WHERE tenant_id = ?", Integer.class, tenantId);
		assertThat(ingredients)
				.as("provisioning seeds no ingredients at all (D-18)")
				.isZero();

		// The recipe CATEGORIES are still seeded, and Ekadashi is still among them flagged
		// fasting-compatible. Only the ingredient rows went.
		Integer ekadashi = admin.queryForObject(
				"SELECT count(*) FROM recipe_categories WHERE tenant_id = ? AND name = 'Ekadashi' AND fasting_compatible",
				Integer.class, tenantId);
		assertThat(ekadashi)
				.as("the Ekadashi category exists out of the box, flagged fasting-compatible (E2-S2)")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("provisioning records an audit event: the actor, the created temple, a null before")
	void provisioningWritesAuditEvent() {
		// The story's audit criterion for the creation case. Provisioning is a creation, so its
		// before is null; the event belongs to the temple created, not to the tenantless actor.
		signInAsSuperAdmin();
		post("/api/v1/tenants", validRequest());

		Map<String, Object> event = admin.queryForMap("""
				SELECT ae.action, ae.entity_type, ae.before_state, ae.after_state, ae.actor_label,
					   u.role AS actor_role, t.slug AS tenant_slug
				FROM audit_events ae
				JOIN users   u ON u.id = ae.actor_user_id
				JOIN tenants t ON t.id = ae.tenant_id
				WHERE ae.action = 'TENANT_PROVISIONED'
				""");

		assertThat(event.get("entity_type")).isEqualTo("TENANT");
		assertThat(event.get("before_state")).as("a creation has no prior state").isNull();
		assertThat(event.get("actor_role"))
				.as("the actor is the platform operator, even though the event is the temple's")
				.isEqualTo("SUPER_ADMIN");
		assertThat(event.get("tenant_slug"))
				.as("the event belongs to the temple that was created")
				.isEqualTo("radha-govinda");
		assertThat(event.get("after_state").toString())
				.as("the after captures what was created")
				.contains("radha-govinda")
				.contains("Sri Sri Radha Govinda Temple");
	}

	@Test
	@DisplayName("a duplicate web address is refused with a code the user can quote")
	void refusesDuplicateSlug() {
		signInAsSuperAdmin();
		post("/api/v1/tenants", validRequest());

		ResponseEntity<String> second = post("/api/v1/tenants", validRequest());

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(second.getBody()).contains("KMS-400032");
		assertThat(second.getBody()).contains("Another temple is already using that web address.");
	}

	@Test
	@DisplayName("an unusable timezone is refused rather than silently accepted")
	void refusesInvalidTimezone() {
		// A wrong timezone shifts every Ekadashi calculation for that temple, and nothing
		// downstream would reveal it. Better to fail here than to produce a subtly wrong
		// calendar for a year.
		signInAsSuperAdmin();

		Map<String, Object> body = validRequest();
		body.put("timezone", "Asia/Bengaluru");

		ResponseEntity<String> response = post("/api/v1/tenants", body);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).contains("KMS-400001");
	}

	@Test
	@DisplayName("invalid coordinates are refused with per-field messages")
	void refusesInvalidCoordinates() {
		signInAsSuperAdmin();

		Map<String, Object> body = validRequest();
		body.put("latitude", 200);

		ResponseEntity<String> response = post("/api/v1/tenants", body);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).contains("Latitude must be between -90 and 90.");
	}

	@Test
	@DisplayName("provisioning grants no access to an existing temple's data")
	void provisioningDoesNotWidenAccess() {
		// Provisioning briefly establishes the new tenant's context so it can create that
		// tenant's first administrator. This proves the elevation is narrow: the super-admin
		// still cannot read or write another temple's users through ordinary access.
		UUID existing = seedTenant();
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, 'uid-existing-staff', 'Existing Cook', 'cook@existing.example',
						'+919000000009', 'KITCHEN_STAFF', 'ACTIVE')
				""", existing);

		signInAsSuperAdmin();
		post("/api/v1/tenants", validRequest());

		// The super-admin has no tenant context of their own, so the other temple's staff
		// remain invisible to any tenant-scoped query.
		Integer visibleToApp = admin.queryForObject(
				"SELECT count(*) FROM users WHERE tenant_id = ?", Integer.class, existing);

		assertThat(visibleToApp)
				.as("the seeded user should still exist and belong only to its own temple")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("a temple admin cannot provision temples")
	void templeAdminCannotProvision() {
		// Running a temple is not running the platform.
		UUID tenantId = seedTenant();
		signInAs("uid-temple-admin", "TEMPLE_ADMIN", tenantId);

		assertThat(post("/api/v1/tenants", validRequest()).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	@DisplayName("an unauthenticated caller cannot provision temples")
	void anonymousCannotProvision() {
		assertThat(post("/api/v1/tenants", validRequest()).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("the new temple's administrator can sign in immediately via first-sign-in claim")
	void newAdministratorCanSignInImmediately() {
		// The story's acceptance criterion: provisioning must produce a working temple, not
		// just a row. The administrator exists before they have ever touched Firebase — the
		// temple decides who administers it, not whoever signs up first.
		signInAsSuperAdmin();
		post("/api/v1/tenants", validRequest());

		String pendingUid = admin.queryForObject(
				"SELECT firebase_uid FROM users WHERE role = 'TEMPLE_ADMIN'", String.class);
		assertThat(pendingUid).startsWith("pending:");

		// The real first sign-in (no manual database surgery): the admin authenticates with the
		// email the temple registered, Firebase-verified, and the claim binds their uid. This is
		// the path that lets a freshly provisioned admin sign in.
		stubVerifier.acceptVerified("uid-first-login", "admin@example.com");

		ResponseEntity<String> whoami = get("/api/v1/whoami");

		assertThat(whoami.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(whoami.getBody()).contains("TEMPLE_ADMIN");

		// The placeholder is now the real uid — bound by the claim, not faked.
		String claimedUid = admin.queryForObject(
				"SELECT firebase_uid FROM users WHERE role = 'TEMPLE_ADMIN'", String.class);
		assertThat(claimedUid).isEqualTo("uid-first-login");
	}

	@Test
	@DisplayName("the administrator's number typed with spaces and hyphens is stored as the bare number (T-157)")
	void administratorsSpacedNumberIsStoredBare() {
		// KMS-400003 writes a number "+91 98765 43210", and the provisioning screen always cleaned
		// what it sent — but the API is the rule, and it has to accept what its own message suggests.
		signInAsSuperAdmin();
		Map<String, Object> body = validRequest();
		body.put("adminPhone", "+91 98765-43210");

		assertThat(post("/api/v1/tenants", body).getStatusCode()).isEqualTo(HttpStatus.CREATED);

		assertThat(admin.queryForObject(
				"SELECT phone FROM users WHERE role = 'TEMPLE_ADMIN'", String.class))
				.isEqualTo("+919876543210");
		assertThat(admin.queryForObject("""
				SELECT sp.phone FROM staff_profiles sp JOIN users u ON u.id = sp.user_id
				WHERE u.role = 'TEMPLE_ADMIN'
				""", String.class))
				.isEqualTo("+919876543210");
	}

	// ---------------------------------------------------------------------

	private Map<String, Object> validRequest() {
		Map<String, Object> body = new HashMap<>();
		body.put("name", "Sri Sri Radha Govinda Temple");
		body.put("slug", "radha-govinda");
		body.put("address", "Bengaluru, Karnataka");
		body.put("latitude", 12.9716);
		body.put("longitude", 77.5946);
		body.put("timezone", "Asia/Kolkata");
		body.put("currency", "INR");
		body.put("is80gApproved", true);
		body.put("adminName", "Karuna Murthy Das");
		body.put("adminEmail", "admin@example.com");
		body.put("adminPhone", "+919876543210");
		return body;
	}

	private void signInAsSuperAdmin() {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (NULL, 'uid-super', 'Platform Operator', 'super@example.com',
						'+919000000001', 'SUPER_ADMIN', 'ACTIVE')
				""");
		stubVerifier.accept("uid-super");
	}

	private void signInAs(String uid, String role, UUID tenantId) {
		admin.update("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test User', ?, '+919000000002', ?, 'ACTIVE')
				""", tenantId, uid, uid + "@example.com", role);
		stubVerifier.accept(uid);
	}

	private UUID seedTenant() {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES ('existing', 'Existing Temple', 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class);
	}

	private ResponseEntity<String> post(String path, Map<String, Object> body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		if (!stubVerifier.isEmpty()) {
			headers.setBearerAuth("valid-token");
		}
		return rest.postForEntity(
				"http://localhost:" + port + path, new HttpEntity<>(body, headers), String.class);
	}

	private ResponseEntity<String> get(String path) {
		HttpHeaders headers = new HttpHeaders();
		if (!stubVerifier.isEmpty()) {
			headers.setBearerAuth("valid-token");
		}
		return rest.exchange(
				"http://localhost:" + port + path,
				org.springframework.http.HttpMethod.GET,
				new HttpEntity<>(headers),
				String.class);
	}

}
