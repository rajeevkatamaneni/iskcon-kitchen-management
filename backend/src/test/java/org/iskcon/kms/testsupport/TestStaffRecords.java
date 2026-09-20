package org.iskcon.kms.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Gives a test's Kitchen Staff and Kitchen Manager accounts the one thing a real one always has: an
 * employment record, in a kitchen that plans its meals here (Epic 12, T-361).
 *
 * <p><strong>Why this exists.</strong> {@code PlannerKitchenGuard} refuses the meal planner to anybody
 * who is not the Temple Admin and has no current staff record — Rajeev, 2026-09-19: "only people whose
 * kitchen plans its meals here can open the meal planner". That is the rule, and it is not weakened
 * here. But the suite predates it: around a hundred and sixty test classes write their own {@code users}
 * row and sign in through {@link StubTokenVerifier}, and a cook who exists only as a {@code users} row
 * is a person no temple could actually have — they were never hired, so they work in no kitchen. Before
 * the guard nothing noticed; with it, 114 tests in eleven classes were refused 403, not because the
 * behaviour under test changed but because the fixture was never complete.
 *
 * <p><strong>Why it is done here and not with a line in each of those classes.</strong> There is no
 * shared helper that makes a test user — each class has its own private {@code insertUser}. The one
 * place every one of them passes through is the token verifier, so that is where the missing half of
 * the fixture is supplied. One place also means the next test class to sign a cook in gets it right
 * without knowing this rule exists, which is the point: a fixture rule that has to be remembered eleven
 * times will be forgotten a twelfth.
 *
 * <p><strong>What it does, exactly.</strong> On the first request of a test made with a given uid, for
 * every {@code users} row with that uid whose role is {@code KITCHEN_STAFF} or {@code KITCHEN_MANAGER}
 * (a uid can name an account at more than one temple — see {@code OwnAccountsAtOtherTemplesIT}) and
 * which has no {@code staff_profiles} row of any status, it writes one: employment ACTIVE, in a kitchen
 * of that temple that is open and uses the planner, with {@code kitchen_needs_check = false}. It writes
 * no schedule template, so the person is rostered for no hours and is counted in no crew figure;
 * nothing that was a number before this becomes a different number because of it.
 *
 * <p><strong>And it takes them away again.</strong> Every row it writes is remembered and deleted after
 * the test body, before the test class's own {@code @AfterEach} runs — {@code AbstractIntegrationTest}
 * registers the callback that does it. That ordering is the whole trick: a test class that ends with
 * {@code DELETE FROM users} knows nothing about this row, and an employment record referencing a user
 * is {@code ON DELETE RESTRICT}, so a row left behind would fail the teardown of a test that never
 * asked for it and leak its temple into the next one. Cleaning up in the same place that writes means
 * no test class has to be told this happens.
 *
 * <p><strong>What it deliberately does not do.</strong>
 * <ul>
 *   <li><b>It never creates a kitchen.</b> A temple with no open planner kitchen is left alone and its
 *       cook is still refused, which is the honest answer for that temple. Creating one would change
 *       what every test that counts a temple's kitchens sees.
 *   <li><b>It never touches an existing employment record</b>, of any status. So a test about somebody
 *       whose employment ended still tests exactly that.
 *   <li><b>Temple Admins and Volunteers get nothing.</b> The admin passes the guard by role, and a
 *       Volunteer is refused for the permission long before a kitchen is asked about.
 * </ul>
 *
 * <p>A class that is specifically testing a cook who was never hired turns this off for itself with
 * {@link StubTokenVerifier#withoutAutomaticStaffRecords()} in its own {@code @BeforeEach} — which runs
 * after {@code AbstractIntegrationTest}'s reset, so the switch has to be thrown per test, like the
 * sign-in it accompanies.
 */
public final class TestStaffRecords {

	/**
	 * The rows written by every context's copy of this, because they all share one database and one
	 * test at a time runs against it. Static so that the cleanup callback can be a plain static
	 * registration on {@code AbstractIntegrationTest} rather than something each context has to hand it.
	 */
	private static final List<UUID> WRITTEN = new ArrayList<>();

	private final JdbcTemplate admin;

	public TestStaffRecords(JdbcTemplate admin) {
		this.admin = admin;
	}

	/**
	 * Ensures every Kitchen Staff / Kitchen Manager account with this uid has an employment record.
	 *
	 * @param firebaseUid the uid the test signed in
	 */
	public void ensureFor(String firebaseUid) {
		List<UUID[]> cooks = admin.query("""
				SELECT u.id, u.tenant_id FROM users u
				WHERE u.firebase_uid = ?
				  AND u.role IN ('KITCHEN_STAFF', 'KITCHEN_MANAGER')
				  AND NOT EXISTS (SELECT 1 FROM staff_profiles sp WHERE sp.user_id = u.id)
				  AND EXISTS (SELECT 1 FROM kitchens k WHERE k.tenant_id = u.tenant_id
							  AND k.status = 'ACTIVE' AND k.uses_meal_planner)
				""", (rs, n) -> new UUID[] { rs.getObject(1, UUID.class), rs.getObject(2, UUID.class) },
				firebaseUid);
		for (UUID[] cook : cooks) {
			UUID userId = cook[0];
			UUID tenant = cook[1];
			// The same kitchen V150 and KitchenService.seedMainKitchenForCurrentTenant would choose:
			// the main kitchen if it plans meals, else the first planner kitchen by name.
			UUID kitchen = admin.queryForObject("""
					SELECT id FROM kitchens
					WHERE tenant_id = ? AND status = 'ACTIVE' AND uses_meal_planner
					ORDER BY is_main DESC, lower(name), id LIMIT 1
					""", UUID.class, tenant);
			UUID written = admin.queryForObject("""
					INSERT INTO staff_profiles (
						tenant_id, user_id, full_name, job_title, employment_type, date_of_joining,
						employment_status, kitchen_id, kitchen_needs_check)
					SELECT ?, ?, COALESCE(u.full_name, 'Test Person'), 'COOK', 'FULL_TIME', DATE '2020-01-01',
						'ACTIVE', ?, false
					FROM users u WHERE u.id = ?
					RETURNING id
					""", UUID.class, tenant, userId, kitchen, userId);
			WRITTEN.add(written);
		}
	}

	/**
	 * Deletes every employment record this wrote. Called after each test body and before the test
	 * class's own teardown; see the class comment for why that order is load-bearing.
	 *
	 * @param admin a privileged {@code JdbcTemplate}; the rows span temples, so a tenant-scoped one
	 *              could not delete them
	 */
	public static void discardAll(JdbcTemplate admin) {
		if (WRITTEN.isEmpty()) {
			return;
		}
		List<UUID> ids = List.copyOf(WRITTEN);
		WRITTEN.clear();
		for (UUID id : ids) {
			// One at a time and never failing: a test that deleted its own staff_profiles already took
			// the row away, and a test that hung something off it (a schedule, a leave request) has
			// deleted that too by the time its own teardown runs — but if it has not, the row it is
			// really about must not be lost to an exception thrown from here.
			try {
				admin.update("DELETE FROM staff_profiles WHERE id = ?", id);
			} catch (RuntimeException e) {
				// Left in place; the test class's own teardown will say so if it matters.
			}
		}
	}
}
