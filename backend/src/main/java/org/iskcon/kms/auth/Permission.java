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

	// --- Platform administration (super-admin only) ---
	MANAGE_TENANTS,
	VIEW_PLATFORM_OPERATIONS,

	// --- Temple administration ---
	MANAGE_USERS,
	VIEW_AUDIT_LOG,
	MANAGE_TEMPLE_SETTINGS,

	// --- Kitchen operations ---
	MANAGE_RECIPES,
	MANAGE_INVENTORY,
	MANAGE_MEAL_PLANS,

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
