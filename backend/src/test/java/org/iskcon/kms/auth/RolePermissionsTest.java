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
				allowed(User.Role.TEMPLE_ADMIN, Permission.OVERRIDE_SATTVIC_ENFORCEMENT),
				allowed(User.Role.TEMPLE_ADMIN, Permission.OVERRIDE_CALENDAR_DATE),
				// A temple admin administers their temple, not the platform.
				denied(User.Role.TEMPLE_ADMIN, Permission.MANAGE_TENANTS),
				denied(User.Role.TEMPLE_ADMIN, Permission.DELETE_TENANT),

				// --- Kitchen staff run the kitchen ---
				allowed(User.Role.KITCHEN_STAFF, Permission.MANAGE_RECIPES),
				allowed(User.Role.KITCHEN_STAFF, Permission.MANAGE_INVENTORY),
				allowed(User.Role.KITCHEN_STAFF, Permission.MANAGE_PURCHASE_ORDERS),
				// The separations that matter: money is not a kitchen concern, and the two
				// overrides carry religious and financial weight that belongs with leadership.
				denied(User.Role.KITCHEN_STAFF, Permission.MANAGE_VENDOR_PAYMENTS),
				denied(User.Role.KITCHEN_STAFF, Permission.VIEW_DONATIONS),
				denied(User.Role.KITCHEN_STAFF, Permission.MANAGE_USERS),
				denied(User.Role.KITCHEN_STAFF, Permission.OVERRIDE_SATTVIC_ENFORCEMENT),
				denied(User.Role.KITCHEN_STAFF, Permission.OVERRIDE_CALENDAR_DATE),

				// --- Volunteers offer seva ---
				allowed(User.Role.VOLUNTEER, Permission.VIEW_OWN_SHIFTS),
				allowed(User.Role.VOLUNTEER, Permission.SIGN_UP_FOR_SHIFTS),
				denied(User.Role.VOLUNTEER, Permission.MANAGE_VOLUNTEER_SHIFTS),
				denied(User.Role.VOLUNTEER, Permission.MANAGE_INVENTORY),
				denied(User.Role.VOLUNTEER, Permission.VIEW_DONATIONS),
				denied(User.Role.VOLUNTEER, Permission.MANAGE_VENDOR_PAYMENTS));
	}

	private static Arguments allowed(User.Role role, Permission permission) {
		return Arguments.of(role, permission, "may", true);
	}

	private static Arguments denied(User.Role role, Permission permission) {
		return Arguments.of(role, permission, "may NOT", false);
	}

	@Test
	@DisplayName("every permission is held by at least one role")
	void noOrphanedPermissions() {
		// A permission nobody holds is either a mistake in the policy or a leftover from a
		// removed feature. Either way it should not sit there unnoticed.
		Stream.of(Permission.values()).forEach(permission ->
				assertThat(Arrays.stream(User.Role.values())
						.anyMatch(role -> RolePermissions.has(role, permission)))
						.as("permission %s is granted to no role", permission)
						.isTrue());
	}

	@Test
	@DisplayName("volunteers hold the fewest permissions of any role")
	void volunteersAreLeastPrivileged() {
		int volunteerCount = RolePermissions.forRole(User.Role.VOLUNTEER).size();

		Stream.of(User.Role.TEMPLE_ADMIN, User.Role.KITCHEN_STAFF).forEach(role ->
				assertThat(RolePermissions.forRole(role).size())
						.as("%s should hold more permissions than a volunteer", role)
						.isGreaterThan(volunteerCount));
	}
}
