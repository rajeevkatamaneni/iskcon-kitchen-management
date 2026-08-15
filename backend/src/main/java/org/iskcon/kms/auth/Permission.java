package org.iskcon.kms.auth;

/**
 * Every distinct thing a user can be allowed to do.
 *
 * <p>Endpoints are annotated with a permission, never with a role. The difference matters: roles
 * change as the temple's structure changes, but "may approve a purchase order" is a stable idea.
 * Re-deciding which roles hold a permission is then a one-line edit in {@link RolePermissions},
 * not a hunt through controllers.
 *
 * <p>Permissions are added as the epics that need them are built. The set below covers Epic 1;
 * later epics extend it.
 */
public enum Permission {

	/**
	 * Join a temple as a volunteer. Held by nobody with a membership — it is the single thing a
	 * verified person can do before they have one, and it is what creates it.
	 */
	JOIN_A_TEMPLE,

	// --- Platform administration (super-admin only) ---
	MANAGE_TENANTS,

	// Permanently deleting a temple and all its data is held apart from MANAGE_TENANTS: creating
	// and viewing temples is routine, but an irreversible whole-tenant purge is a distinct, graver
	// capability that a future role split may want to grant separately.
	DELETE_TENANT,

	VIEW_PLATFORM_OPERATIONS,

	// --- Temple administration ---
	MANAGE_USERS,
	VIEW_AUDIT_LOG,
	MANAGE_TEMPLE_SETTINGS,

	// --- Kitchen operations ---
	MANAGE_RECIPES,
	MANAGE_INVENTORY,
	MANAGE_MEAL_PLANS,

	// Setting which ingredients are sattvic-prohibited is a religious-policy decision, held apart
	// from ordinary recipe/ingredient editing so that a Kitchen Staff member who may add
	// ingredients still cannot decide what is prohibited (E2-S1).
	MANAGE_SATTVIC_POLICY,

	// Kitchen staff make routine stock adjustments, but a large one (over 20% of what's on hand)
	// needs a Temple Admin to approve it — a big write-off is a leadership call, and the split
	// makes an unusual correction visible rather than routine (E3-S7).
	APPROVE_LARGE_STOCK_ADJUSTMENT,

	// --- Ordering ---
	MANAGE_VENDORS,
	MANAGE_PURCHASE_ORDERS,

	// --- Workforce ---
	MANAGE_STAFF_SCHEDULE,
	MANAGE_VOLUNTEER_SHIFTS,
	VIEW_OWN_SHIFTS,
	SIGN_UP_FOR_SHIFTS,

	// --- Money ---
	MANAGE_VENDOR_PAYMENTS,
	VIEW_DONATIONS,
	MANAGE_WISHLIST,

	// --- Overrides that carry religious or financial weight ---
	OVERRIDE_SATTVIC_ENFORCEMENT,
	OVERRIDE_CALENDAR_DATE,
}
