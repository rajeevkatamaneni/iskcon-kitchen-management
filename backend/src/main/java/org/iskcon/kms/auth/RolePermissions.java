package org.iskcon.kms.auth;

import static org.iskcon.kms.auth.Permission.*;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.iskcon.kms.user.User;

/**
 * The authorisation policy, in one place.
 *
 * <p>This class is intended to be readable as a document: someone asking "can kitchen staff pay a
 * vendor?" should be able to answer it here, without reading any controller. Scattering these
 * decisions across annotations and service methods is how systems end up with nobody able to say
 * what a role can actually do.
 *
 * <p>Deliberately not stored in the database. Roles are fixed for release 1 (REQUIREMENTS.md §2),
 * and keeping the policy in code means it is version-controlled, reviewable in a diff, and
 * testable — none of which is true of rows someone can edit in production.
 */
public final class RolePermissions {

	private static final Map<User.Role, Set<Permission>> PERMISSIONS_BY_ROLE = Map.of(

			// Platform operator. Provisions temples; deliberately holds no kitchen or money
			// permissions — running the platform is not the same as running a temple, and the
			// super-admin should not be able to quietly read a temple's donations.
			User.Role.SUPER_ADMIN,
			EnumSet.of(
					MANAGE_TENANTS,
					DELETE_TENANT,
					VIEW_PLATFORM_OPERATIONS,
					RAISE_PLATFORM_NOTICE,
					WITHDRAW_ANY_PLATFORM_NOTICE,
					MANAGE_RECIPE_LIBRARY),

			// Runs one temple. Everything within it, including the overrides that carry
			// religious or financial weight.
			User.Role.TEMPLE_ADMIN,
			EnumSet.of(
					MANAGE_USERS,
					VIEW_AUDIT_LOG,
					MANAGE_TEMPLE_SETTINGS,
					MANAGE_RECIPES,
					MANAGE_INVENTORY,
					MANAGE_MEAL_PLANS,
					MANAGE_VENDORS,
					MANAGE_PURCHASE_ORDERS,
					MANAGE_STAFF,
					// Conduct notes are held apart from the rest of the employment record, and narrowly:
					// the Temple Admin alone, never the Kitchen Manager and never Kitchen Staff. The
					// danger here is the reading, not the writing — a note is a permanent statement about
					// how a real person behaved, and the first thing a shared permission would produce is
					// a kitchen manager reading their colleague's warning. Same argument as E6-S8 D9, one
					// step further in: that split kept everyone's date of birth and PAN out of the roster
					// permission, and this one keeps their conduct out of the hiring one.
					MANAGE_STAFF_CONDUCT_NOTES,
					MANAGE_STAFF_SCHEDULE,
					APPROVE_LEAVE,
					REQUEST_OWN_LEAVE,
					MANAGE_VOLUNTEER_SHIFTS,
					VIEW_OWN_SHIFTS,
					RAISE_PLATFORM_NOTICE,
					MANAGE_COMMUNICATIONS,
					MANAGE_VENDOR_PAYMENTS,
					VIEW_DONATIONS,
					MANAGE_WISHLIST,
					MANAGE_SATTVIC_POLICY,
					APPROVE_LARGE_STOCK_ADJUSTMENT,
					MANAGE_KITCHENS,
					REQUEST_INGREDIENTS,
					APPROVE_INGREDIENT_REQUESTS,
					ISSUE_INGREDIENTS,
					OVERRIDE_SATTVIC_ENFORCEMENT,
					OVERRIDE_CALENDAR_DATE),

			// Runs the kitchen's people. Everything kitchen staff hold, and the two decisions that
			// make somebody a manager rather than a cook: the roster, and the leave the roster has to
			// bend around. Note what is still absent — MANAGE_STAFF, which is what gates hiring,
			// salary and PAN. A manager deciding Thursday's shifts has no business reading pay.
			User.Role.KITCHEN_MANAGER,
			EnumSet.of(
					MANAGE_RECIPES,
					MANAGE_INVENTORY,
					MANAGE_MEAL_PLANS,
					MANAGE_VENDORS,
					MANAGE_PURCHASE_ORDERS,
					MANAGE_VOLUNTEER_SHIFTS,
					VIEW_OWN_SHIFTS,
					MANAGE_STAFF_SCHEDULE,
					APPROVE_LEAVE,
					REQUEST_OWN_LEAVE,
					// The temple's storekeeper is a Kitchen Manager. Answering a request for
					// ingredients and handing them over is the same kind of act as answering leave:
					// it is what the role was appointed to do (E10 design D4).
					REQUEST_INGREDIENTS,
					APPROVE_INGREDIENT_REQUESTS,
					ISSUE_INGREDIENTS),

			// Day-to-day kitchen work. Note what is absent: no payments, no donations, no user
			// management, and neither override. A sattvic violation or a calendar correction is
			// a decision for temple leadership, not something to resolve mid-shift.
			User.Role.KITCHEN_STAFF,
			EnumSet.of(
					MANAGE_RECIPES,
					MANAGE_INVENTORY,
					MANAGE_MEAL_PLANS,
					MANAGE_VENDORS,
					MANAGE_PURCHASE_ORDERS,
					MANAGE_VOLUNTEER_SHIFTS,
					VIEW_OWN_SHIFTS,
					REQUEST_OWN_LEAVE,
					// Asking, and only asking. A cook who has run out of ghee says so; somebody else
					// decides whether the store can spare it, or the request would be its own answer.
					REQUEST_INGREDIENTS),

			// Devotees offering seva. Their own shifts, and signing up for more.
			User.Role.VOLUNTEER,
			EnumSet.of(
					VIEW_OWN_SHIFTS,
					SIGN_UP_FOR_SHIFTS));

	/**
	 * What someone verified by Firebase may do before they belong to any temple. Deliberately one
	 * thing: choose where they serve. Everything else on the platform belongs to a temple, and they
	 * are not yet of one.
	 */
	public static EnumSet<Permission> forNoMembership() {
		return EnumSet.of(JOIN_A_TEMPLE);
	}

	private RolePermissions() {
	}

	public static Set<Permission> forRole(User.Role role) {
		return PERMISSIONS_BY_ROLE.getOrDefault(role, EnumSet.noneOf(Permission.class));
	}

	public static boolean has(User.Role role, Permission permission) {
		return forRole(role).contains(permission);
	}
}
