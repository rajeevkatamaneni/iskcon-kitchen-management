package org.iskcon.kms.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.communication.CommunicationService;
import org.iskcon.kms.document.DocumentService;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.inventory.LowStockAlertService;
import org.iskcon.kms.inventory.LowStockDigestRunner;
import org.iskcon.kms.kitchen.CreateKitchenRequest;
import org.iskcon.kms.kitchen.KitchenService;
import org.iskcon.kms.staff.EmploymentType;
import org.iskcon.kms.staff.HireStaffRequest;
import org.iskcon.kms.staff.JobTitle;
import org.iskcon.kms.staff.StaffEmploymentService;
import org.iskcon.kms.user.User;
import org.iskcon.kms.user.UserManagementService;
import org.iskcon.kms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One person with an account at two temples, signed in at one of them, must see only that temple.
 *
 * <p><strong>Why this can go wrong at all.</strong> The read policy on {@code users} admits a row
 * when its temple is the request's temple <em>or</em> when its Firebase uid is the caller's own
 * (V2, V4). That second branch is how sign-in finds a person's memberships before any temple is
 * chosen, and it is deliberate. But the authentication filter sets the caller's uid for the whole
 * request, not only for the lookup, so every later query on {@code users} that names no temple also
 * returns the caller's own accounts at their other temples. Since V52 a person may have several.
 *
 * <p>So "RLS scopes it to the temple" is true of every other table and only nearly true of this one.
 * Each test below is one query that relied on it: the newsletter audience, the user register, the
 * status change, the document's author, a kitchen's in-charge, hiring an existing account, and the
 * low-stock digest. The fix in each case is the same — name the temple in the SQL — and each test
 * was run red against the code before that condition was added.
 *
 * <p><strong>Context.</strong> The request is reproduced exactly as {@code AuthenticationFilter}
 * leaves it: {@code TenantContext.set(temple)} plus {@code setAuthLookupUid(uid)}, through the
 * application's own tenant-aware DataSource as the unprivileged {@code kms_app} role, like
 * {@link RowLevelSecurityIT}. No annotations beyond the base class, so this shares the suite's
 * commonest cached context rather than adding one.
 *
 * <p>The writes are not at issue, and {@link RowLevelSecurityIT} pins that: the insert, update and
 * delete policies on {@code users} are temple-only, so an own account elsewhere can be read but
 * never changed.
 */
class OwnAccountsAtOtherTemplesIT extends AbstractIntegrationTest {

	/** The one person this is about. Two accounts, one uid. */
	private static final String PERSON_UID = "uid-two-temples";

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private CommunicationService communications;

	@Autowired
	private UserManagementService userManagement;

	@Autowired
	private DocumentService documents;

	@Autowired
	private KitchenService kitchens;

	@Autowired
	private StaffEmploymentService employment;

	@Autowired
	private LowStockAlertService lowStockAlerts;

	@Autowired
	private LowStockDigestRunner lowStockRunner;

	private JdbcTemplate admin;

	private UUID templeA;
	private UUID templeB;

	/** The person's account at temple A, where they are signed in: its administrator. */
	private UUID personAtA;
	/** The same person's account at temple B, where they are a devotee. */
	private UUID personAtB;
	/** Somebody else entirely, a devotee at temple A. */
	private UUID devoteeAtA;
	/** And somebody else at temple B, who must never appear either way. */
	private UUID strangerAtB;

	@BeforeEach
	void setUp() {
		TenantContext.clear();
		admin = new JdbcTemplate(adminDataSource());

		templeA = insertTenant("radha-govinda", "Sri Sri Radha Govinda Temple");
		templeB = insertTenant("radha-krishna", "Sri Sri Radha Krishna Temple");

		// Consent is withheld from the administrator on purpose: a notification to somebody who has
		// not consented is recorded as SUPPRESSED and never queued, so the digest can run to the end
		// in a context with no scheduler and leave a row naming every person it chose.
		personAtA = insertUser(templeA, PERSON_UID, "two-temples@example.com", "+919800000101",
				"TEMPLE_ADMIN", false);
		personAtB = insertUser(templeB, PERSON_UID, "two-temples@example.com", "+919800000101",
				"VOLUNTEER", true);
		devoteeAtA = insertUser(templeA, "uid-devotee-a", "devotee-a@example.com", "+919800000102",
				"VOLUNTEER", true);
		strangerAtB = insertUser(templeB, "uid-stranger-b", "stranger-b@example.com", "+919800000103",
				"VOLUNTEER", true);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		admin.execute("DELETE FROM notification_attempts");
		admin.execute("DELETE FROM notifications");
		admin.execute("DELETE FROM low_stock_digest_runs");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM documents");
		admin.execute("DELETE FROM recipes");
		admin.execute("DELETE FROM recipe_categories");
		admin.execute("DELETE FROM staff_schedule_template");
		admin.execute("DELETE FROM staff_profiles");
		admin.execute("DELETE FROM kitchens");
		admin.execute("DELETE FROM communication_recipients");
		admin.execute("DELETE FROM communications");
		admin.execute("DELETE FROM audit_events");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	// ---- The newsletter audience --------------------------------------------------------------

	@Test
	@DisplayName("a newsletter's audience is this temple's devotees, not the sender's own account elsewhere")
	void audienceLeavesOutTheSendersOtherAccounts() {
		UUID draft = admin.queryForObject("""
				INSERT INTO communications (tenant_id, category, channel, subject, body_html, body_text,
						created_by)
				VALUES (?, 'NEWSLETTER', 'EMAIL', 'Janmashtami', '<p>Come early.</p>', 'Come early.', ?)
				RETURNING id
				""", UUID.class, templeA, personAtA);

		signedInAtA();

		// Both are asked, because both are what a Temple Admin acts on: the count on the confirmation
		// before sending, and the list recordSend writes a recipient row for.
		assertThat(communications.audienceFor(communications.get(draft)))
				.as("temple A's devotee only — the administrator's own devotee account at temple B was "
						+ "being put into temple A's newsletter")
				.containsExactly(devoteeAtA);
		assertThat(communications.audienceSize(draft))
				.as("the reach count shown before sending")
				.isEqualTo(1);
	}

	// ---- The user register, and changing someone's status -------------------------------------

	@Test
	@DisplayName("the temple's user list does not show the administrator's own accounts at other temples")
	void userListIsThisTempleOnly() {
		signedInAtA();

		assertThat(userManagement.listUsers(null))
				.extracting(summary -> summary.id())
				.as("everyone at temple A, and nobody from temple B — including the admin's own account there")
				.containsExactlyInAnyOrder(personAtA, devoteeAtA);

		assertThat(userManagement.listUsers(User.Role.VOLUNTEER))
				.extracting(summary -> summary.id())
				.as("the devotee register, which is where the account at temple B showed up as a devotee")
				.containsExactly(devoteeAtA);
	}

	@Test
	@DisplayName("an administrator cannot 'disable' their own account at another temple from this one")
	void statusChangeRefusesOwnAccountElsewhere() {
		signedInAtA();
		AuthenticatedUser actor = actingAsPersonAtA();

		// Before the fix this did not throw. The UPDATE matched nothing, because writes are temple-only,
		// and then an audit entry was written anyway saying temple A had disabled a user from temple B.
		assertThatThrownBy(() -> userManagement.setStatus(actor, personAtB, "DISABLED"))
				.isInstanceOfSatisfying(ApplicationException.class,
						e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));

		assertThat(admin.queryForObject(
				"SELECT count(*) FROM audit_events WHERE entity_id = ?", Integer.class, personAtB))
				.as("no audit entry about somebody who is not this temple's").isZero();
		assertThat(admin.queryForObject("SELECT status FROM users WHERE id = ?", String.class, personAtB))
				.isEqualTo("ACTIVE");
	}

	// ---- Who asked for a document ---------------------------------------------------------------

	@Test
	@DisplayName("a person with two temples can request a document, and it is recorded as this temple's account")
	void documentAuthorIsThisTemplesAccount() {
		UUID category = admin.queryForObject("""
				INSERT INTO recipe_categories (tenant_id, name, fasting_compatible)
				VALUES (?, 'Rice', false) RETURNING id
				""", UUID.class, templeA);
		UUID recipe = admin.queryForObject("""
				INSERT INTO recipes (tenant_id, name, category_id, base_yield_qty, base_yield_unit, method)
				VALUES (?, 'Plain Rice', ?, 100, 'KG', 'Boil the rice.') RETURNING id
				""", UUID.class, templeA, category);

		signedInAtA();

		// The request looks the author up by uid. With two accounts that returned two rows, and
		// queryForObject threw IncorrectResultSizeDataAccessException before anything was written:
		// every document request by anybody with a second temple failed.
		//
		// This context has no scheduler, so a request that gets past the lookup then fails to enqueue.
		// Run inside an outer transaction, the documents row it inserted is still readable on the same
		// connection before everything is rolled back, and that row says whose account was recorded.
		UUID createdBy = new TransactionTemplate(transactionManager).execute(status -> {
			status.setRollbackOnly();
			assertThatThrownBy(() -> documents.requestRecipePdf(recipe, null, "en"))
					.as("past the author lookup, and stopped only for want of a worker")
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("No scheduler available");
			return jdbc.queryForObject(
					"SELECT created_by FROM documents WHERE recipe_id = ?", UUID.class, recipe);
		});

		assertThat(createdBy)
				.as("the document is recorded against the person's account at this temple")
				.isEqualTo(personAtA);
	}

	// ---- Ids a client supplies ------------------------------------------------------------------

	@Test
	@DisplayName("a kitchen cannot be put in the charge of the administrator's own account at another temple")
	void kitchenInChargeMustBeThisTemples() {
		signedInAtA();
		AuthenticatedUser actor = actingAsPersonAtA();

		// The id is in the request body. Before the fix the existence check found the account at temple
		// B, and the foreign key, which knows nothing of temples, accepted it.
		assertThatThrownBy(() -> kitchens.create(actor, kitchen("Main kitchen", personAtB)))
				.isInstanceOfSatisfying(ApplicationException.class,
						e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
		assertThat(admin.queryForObject("SELECT count(*) FROM kitchens", Integer.class)).isZero();

		// And the same call with the same person's account here is fine, so the refusal is about the
		// temple and not a create that is broken for some other reason.
		kitchens.create(actor, kitchen("Main kitchen", personAtA));
		assertThat(admin.queryForObject(
				"SELECT in_charge_user_id FROM kitchens WHERE tenant_id = ?", UUID.class, templeA))
				.isEqualTo(personAtA);
	}

	@Test
	@DisplayName("hiring 'an existing devotee' cannot name the administrator's own account at another temple")
	void hireRefusesOwnAccountElsewhere() {
		signedInAtA();
		AuthenticatedUser actor = actingAsPersonAtA();

		// existingUserId comes from the request, chosen off the devotee register — which, before its
		// own fix, listed the administrator's account at temple B. Hiring it wrote a staff record at
		// temple A pointing at a user row that belongs to temple B.
		assertThatThrownBy(() -> employment.hire(actor, hireExisting(personAtB)))
				.isInstanceOfSatisfying(ApplicationException.class,
						e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
		assertThat(admin.queryForObject("SELECT count(*) FROM staff_profiles", Integer.class)).isZero();

		// This temple's own devotee is still hired in exactly the same way.
		employment.hire(actor, hireExisting(devoteeAtA));
		assertThat(admin.queryForObject(
				"SELECT user_id FROM staff_profiles WHERE tenant_id = ?", UUID.class, templeA))
				.isEqualTo(devoteeAtA);
	}

	// ---- The low-stock digest -------------------------------------------------------------------

	@Test
	@DisplayName("the nightly digest, on a job thread with no signed-in person, reaches only the temple's own staff")
	void digestInAJobReachesOnlyThisTemple() throws InterruptedException {
		personCooksAtBToo();
		somethingIsLowAtA();

		// A fresh thread, as a Quartz worker is: nothing has ever called setAuthLookupUid on it, which
		// only AuthenticationFilter does. The sweep sets each temple as the tenant in turn, exactly as
		// LowStockDigestJob runs it.
		AtomicReference<String> uidOnThatThread = new AtomicReference<>("not read");
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread worker = new Thread(() -> {
			try {
				uidOnThatThread.set(TenantContext.getAuthLookupUid().orElse(null));
				lowStockRunner.sweep();
			} catch (Throwable t) {
				failure.set(t);
			} finally {
				TenantContext.clear();
			}
		}, "digest-like-a-quartz-worker");
		worker.start();
		worker.join(60_000);

		assertThat(failure.get()).isNull();
		assertThat(uidOnThatThread.get()).as("the job thread carries no signed-in uid").isNull();
		assertThat(digestRecipientsAt(templeA))
				.as("true with or without the temple named in the SQL: with no uid the escape matches nothing")
				.containsExactly(personAtA);
	}

	@Test
	@DisplayName("the digest names the temple itself, so it stays right even if it is ever sent from a request")
	void digestFromARequestReachesOnlyThisTemple() {
		personCooksAtBToo();
		somethingIsLowAtA();

		// Nothing sends a digest from a request today. This is the case the added condition is for: a
		// "send it now" button would run it with the caller's uid set, and the caller's own kitchen
		// account at temple B would be sent temple A's shopping list.
		signedInAtA();
		assertThat(lowStockAlerts.sendDailyDigest()).isTrue();
		TenantContext.clear();

		assertThat(digestRecipientsAt(templeA)).containsExactly(personAtA);
	}

	// ---------------------------------------------------------------------------------------------

	/** The request exactly as AuthenticationFilter leaves it for this person signed in at temple A. */
	private void signedInAtA() {
		TenantContext.clear();
		TenantContext.set(templeA);
		TenantContext.setAuthLookupUid(PERSON_UID);
	}

	private AuthenticatedUser actingAsPersonAtA() {
		return new AuthenticatedUser(userRepository.findById(personAtA).orElseThrow());
	}

	/** The person also holds a kitchen role at temple B, and has consented to nothing there either. */
	private void personCooksAtBToo() {
		admin.update("UPDATE users SET role = 'KITCHEN_STAFF', contact_consent_at = NULL WHERE id = ?",
				personAtB);
	}

	/** An item at temple A below its threshold with nothing on hand; temple B has nothing low. */
	private void somethingIsLowAtA() {
		UUID dal = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, 'Toor Dal', 'Pulses', 'KG') RETURNING id
				""", UUID.class, templeA);
		admin.update("""
				INSERT INTO inventory_items (tenant_id, ingredient_id, reorder_threshold)
				VALUES (?, ?, 5::numeric)
				""", templeA, dal);
	}

	private List<UUID> digestRecipientsAt(UUID tenant) {
		return admin.queryForList("""
				SELECT recipient_user_id FROM notifications
				WHERE tenant_id = ? AND template = 'LOW_STOCK_DIGEST'
				""", UUID.class, tenant);
	}

	private static CreateKitchenRequest kitchen(String name, UUID inCharge) {
		return new CreateKitchenRequest(name, null, null, true, false, inCharge, null);
	}

	private static HireStaffRequest hireExisting(UUID userId) {
		return new HireStaffRequest(
				userId, "Hired Person", null, null, JobTitle.COOK, null, EmploymentType.FULL_TIME,
				LocalDate.of(2026, 9, 1), null, null, null, null, null, null, null, null, null, null, null);
	}

	private UUID insertTenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);
	}

	private UUID insertUser(UUID tenant, String uid, String email, String phone, String role,
			boolean consented) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status,
						contact_consent_at)
				VALUES (?, ?, 'Test Person', ?, ?, ?, 'ACTIVE', CASE WHEN ? THEN now() END)
				RETURNING id
				""", UUID.class, tenant, uid, email, phone, role, consented);
	}
}
