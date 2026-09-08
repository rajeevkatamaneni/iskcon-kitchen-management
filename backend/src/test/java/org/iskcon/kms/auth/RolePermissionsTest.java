package org.iskcon.kms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Stream;
import org.iskcon.kms.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The authorisation policy as an executable specification.
 *
 * <p>Every role/permission pair is asserted explicitly rather than derived from
 * {@link RolePermissions} — deriving the expectation from the thing under test would pass no
 * matter what the policy said. Written out, a change to the policy fails a test and forces the
 * change to be deliberate.
 */
class RolePermissionsTest {

	@ParameterizedTest(name = "{0} {2} {1}")
	@MethodSource("thePolicy")
	@DisplayName("the authorisation policy is exactly as documented")
	void policyMatrix(User.Role role, Permission permission, String ignoredLabel, boolean expected) {
		assertThat(RolePermissions.has(role, permission)).isEqualTo(expected);
	}

	static Stream<Arguments> thePolicy() {
		return Stream.of(
				// --- Super-admin runs the platform, not any temple ---
				allowed(User.Role.SUPER_ADMIN, Permission.MANAGE_TENANTS),
				allowed(User.Role.SUPER_ADMIN, Permission.DELETE_TENANT),
				allowed(User.Role.SUPER_ADMIN, Permission.VIEW_PLATFORM_OPERATIONS),
				// Deliberate: provisioning temples must not confer the ability to read their
				// donations or alter their recipes.
				denied(User.Role.SUPER_ADMIN, Permission.VIEW_DONATIONS),
				denied(User.Role.SUPER_ADMIN, Permission.MANAGE_RECIPES),
				denied(User.Role.SUPER_ADMIN, Permission.MANAGE_VENDOR_PAYMENTS),

				// --- Temple admin runs one temple, completely ---
				allowed(User.Role.TEMPLE_ADMIN, Permission.MANAGE_USERS),
				allowed(User.Role.TEMPLE_ADMIN, Permission.VIEW_AUDIT_LOG),
				allowed(User.Role.TEMPLE_ADMIN, Permission.MANAGE_VENDOR_PAYMENTS),
				allowed(User.Role.TEMPLE_ADMIN, Permission.VIEW_DONATIONS),
				allowed(User.Role.TEMPLE_ADMIN, Permission.MANAGE_DIETARY_POLICY),
				allowed(User.Role.TEMPLE_ADMIN, Permission.OVERRIDE_CALENDAR_DATE),
				// A temple admin administers their temple, not the platform.
				denied(User.Role.TEMPLE_ADMIN, Permission.MANAGE_TENANTS),
				denied(User.Role.TEMPLE_ADMIN, Permission.DELETE_TENANT),

				// --- Kitchen staff run the kitchen ---
				allowed(User.Role.KITCHEN_STAFF, Permission.MANAGE_RECIPES),
				allowed(User.Role.KITCHEN_STAFF, Permission.MANAGE_INVENTORY),
				allowed(User.Role.KITCHEN_STAFF, Permission.MANAGE_PURCHASE_ORDERS),
				// The separations that matter: money is not a kitchen concern, deciding what is
				// prohibited on a fasting day is a religious-policy call, and moving a festival date
				// carries the same weight — all three belong with leadership.
				denied(User.Role.KITCHEN_STAFF, Permission.MANAGE_VENDOR_PAYMENTS),
				denied(User.Role.KITCHEN_STAFF, Permission.VIEW_DONATIONS),
				denied(User.Role.KITCHEN_STAFF, Permission.MANAGE_USERS),
				denied(User.Role.KITCHEN_STAFF, Permission.MANAGE_DIETARY_POLICY),
				denied(User.Role.KITCHEN_STAFF, Permission.OVERRIDE_CALENDAR_DATE),

				// --- A kitchen manager runs the temple's people, not its money ---
				allowed(User.Role.KITCHEN_MANAGER, Permission.MANAGE_STAFF_SCHEDULE),
				allowed(User.Role.KITCHEN_MANAGER, Permission.APPROVE_LEAVE),
				allowed(User.Role.KITCHEN_MANAGER, Permission.MANAGE_MEAL_PLANS),
				allowed(User.Role.KITCHEN_MANAGER, Permission.REQUEST_OWN_LEAVE),
				// The separation the build brief turns on (§7): the staff register is the only place
				// salary and PAN appear, and it is behind MANAGE_STAFF. A manager who could hold this
				// would be reading pay on their way to editing Thursday's hours.
				denied(User.Role.KITCHEN_MANAGER, Permission.MANAGE_STAFF),
				denied(User.Role.KITCHEN_MANAGER, Permission.MANAGE_USERS),
				denied(User.Role.KITCHEN_MANAGER, Permission.VIEW_DONATIONS),
				denied(User.Role.KITCHEN_MANAGER, Permission.MANAGE_VENDOR_PAYMENTS),

				// --- Conduct notes are held apart from the rest of the employment record ---
				// The narrowest grant in the workforce area, and deliberately narrower than
				// MANAGE_STAFF: a note is a permanent statement about how a colleague behaved, and
				// the danger is somebody reading it on their way to somewhere else. Same argument as
				// E6-S8 D9, one step further in (E6-S16).
				allowed(User.Role.TEMPLE_ADMIN, Permission.MANAGE_STAFF_CONDUCT_NOTES),
				denied(User.Role.KITCHEN_MANAGER, Permission.MANAGE_STAFF_CONDUCT_NOTES),
				denied(User.Role.KITCHEN_STAFF, Permission.MANAGE_STAFF_CONDUCT_NOTES),
				denied(User.Role.VOLUNTEER, Permission.MANAGE_STAFF_CONDUCT_NOTES),
				// The operator runs the platform and reads no temple's employment records.
				denied(User.Role.SUPER_ADMIN, Permission.MANAGE_STAFF_CONDUCT_NOTES),

				// --- Booking the engineer is not the same act as finding the machine broken ---
				// E3-S10 D10. Everyone who runs the kitchen keeps MANAGE_INVENTORY and goes on
				// registering equipment and moving its condition — they are the ones standing in
				// front of the grinder when it stops. Agreeing that it is serviced every six
				// months, recording that it was, and keeping the list of firms who do it commits
				// the temple to money and to a date, and stays with the Temple Admin. Same gravity
				// split as APPROVE_LARGE_STOCK_ADJUSTMENT, and it is what makes "this belongs on
				// the Temple Admin's dashboard" enforceable rather than a matter of which screen a
				// role happens to land on.
				allowed(User.Role.TEMPLE_ADMIN, Permission.MANAGE_EQUIPMENT_SERVICING),
				denied(User.Role.KITCHEN_MANAGER, Permission.MANAGE_EQUIPMENT_SERVICING),
				denied(User.Role.KITCHEN_STAFF, Permission.MANAGE_EQUIPMENT_SERVICING),
				denied(User.Role.VOLUNTEER, Permission.MANAGE_EQUIPMENT_SERVICING),
				// The operator provisions temples and services none of their machines.
				denied(User.Role.SUPER_ADMIN, Permission.MANAGE_EQUIPMENT_SERVICING),
				// And the split is only worth anything if the wider permission stayed where it was.
				allowed(User.Role.KITCHEN_MANAGER, Permission.MANAGE_INVENTORY),

				// --- Striking a gift is not the same act as recording one (T-012, D-4) ---
				// Forced rather than chosen, which is the interesting part: VIEW_DONATIONS is already
				// the Temple Admin's alone, so any wider grant here would let somebody strike a
				// record they cannot read — a correction made blind to what is being corrected.
				// Recording deliberately stays on MANAGE_INVENTORY (D-5), so a cook may create an
				// 80G-relevant entry and never undo one; that asymmetry is inherited knowingly,
				// because an in-kind gift is a sack of rice at the gate and a cook is who receives it.
				allowed(User.Role.TEMPLE_ADMIN, Permission.VOID_DONATION),
				denied(User.Role.KITCHEN_MANAGER, Permission.VOID_DONATION),
				denied(User.Role.KITCHEN_STAFF, Permission.VOID_DONATION),
				denied(User.Role.VOLUNTEER, Permission.VOID_DONATION),
				// The operator provisions temples and touches no temple's books.
				denied(User.Role.SUPER_ADMIN, Permission.VOID_DONATION),

				// Kitchen staff run the roster for nobody, and answer nobody's leave.
				denied(User.Role.KITCHEN_STAFF, Permission.MANAGE_STAFF_SCHEDULE),
				denied(User.Role.KITCHEN_STAFF, Permission.APPROVE_LEAVE),
				allowed(User.Role.KITCHEN_STAFF, Permission.REQUEST_OWN_LEAVE),

				// --- The notice board reaches every temple, so who may post to it is narrow ---
				allowed(User.Role.SUPER_ADMIN, Permission.RAISE_PLATFORM_NOTICE),
				allowed(User.Role.SUPER_ADMIN, Permission.WITHDRAW_ANY_PLATFORM_NOTICE),
				allowed(User.Role.TEMPLE_ADMIN, Permission.RAISE_PLATFORM_NOTICE),
				// The operator's takedown is what stands in for pre-moderation, so it stays theirs.
				denied(User.Role.TEMPLE_ADMIN, Permission.WITHDRAW_ANY_PLATFORM_NOTICE),
				denied(User.Role.KITCHEN_MANAGER, Permission.RAISE_PLATFORM_NOTICE),
				denied(User.Role.KITCHEN_STAFF, Permission.RAISE_PLATFORM_NOTICE),

				// --- Volunteers offer seva ---
				allowed(User.Role.VOLUNTEER, Permission.VIEW_OWN_SHIFTS),
				allowed(User.Role.VOLUNTEER, Permission.SIGN_UP_FOR_SHIFTS),
				denied(User.Role.VOLUNTEER, Permission.MANAGE_VOLUNTEER_SHIFTS),
				denied(User.Role.VOLUNTEER, Permission.MANAGE_INVENTORY),
				denied(User.Role.VOLUNTEER, Permission.VIEW_DONATIONS),
				denied(User.Role.VOLUNTEER, Permission.MANAGE_VENDOR_PAYMENTS),
				denied(User.Role.VOLUNTEER, Permission.REQUEST_OWN_LEAVE));
	}

	private static Arguments allowed(User.Role role, Permission permission) {
		return Arguments.of(role, permission, "may", true);
	}

	private static Arguments denied(User.Role role, Permission permission) {
		return Arguments.of(role, permission, "may NOT", false);
	}

	@Test
	@DisplayName("every permission is held by at least one role, or by the person with no role")
	void noOrphanedPermissions() {
		// A permission nobody holds is either a mistake in the policy or a leftover from a
		// removed feature. Either way it should not sit there unnoticed. The one exception is
		// deliberate and named: joining a temple belongs to somebody who has no role yet, because
		// it is the act that gives them one.
		Stream.of(Permission.values()).forEach(permission ->
				assertThat(Arrays.stream(User.Role.values())
						.anyMatch(role -> RolePermissions.has(role, permission))
						|| RolePermissions.forNoMembership().contains(permission))
						.as("permission %s is granted to no role", permission)
						.isTrue());
	}

	@Test
	@DisplayName("someone with no temple may do exactly one thing: choose one")
	void noMembershipHoldsOnlyTheJoin() {
		assertThat(RolePermissions.forNoMembership())
				.as("anything else here would be a permission granted to a person no temple has vouched for")
				.containsExactly(Permission.JOIN_A_TEMPLE);
	}

	@Test
	@DisplayName("volunteers hold the fewest permissions of any role")
	void volunteersAreLeastPrivileged() {
		int volunteerCount = RolePermissions.forRole(User.Role.VOLUNTEER).size();

		Stream.of(User.Role.TEMPLE_ADMIN, User.Role.KITCHEN_MANAGER, User.Role.KITCHEN_STAFF).forEach(role ->
				assertThat(RolePermissions.forRole(role).size())
						.as("%s should hold more permissions than a volunteer", role)
						.isGreaterThan(volunteerCount));
	}
}
