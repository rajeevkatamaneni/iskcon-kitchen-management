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

	// Hiring, employing and letting go — held apart from MANAGE_STAFF_SCHEDULE because it is a
	// different kind of act: it is the only door into the temple's own roles (a hire may be granted
	// Temple Admin), and it holds a person's date of birth, address and PAN. Editing next week's
	// hours is routine; deciding who works here is not (E6-S8).
	MANAGE_STAFF,

	MANAGE_STAFF_SCHEDULE,
	MANAGE_VOLUNTEER_SHIFTS,
	VIEW_OWN_SHIFTS,
	SIGN_UP_FOR_SHIFTS,

	// Answering a request for time off, and recording it on behalf of somebody who has no app to
	// ask from. Held apart from MANAGE_STAFF because it is the one workforce decision a Kitchen
	// Manager makes daily — they run the roster, and leave is what the roster has to bend around.
	// Deliberately not folded into MANAGE_STAFF_SCHEDULE: editing next Tuesday's hours and granting
	// a fortnight's sick leave are not the same act, even though both end up on the same grid.
	APPROVE_LEAVE,

	// Asking for time off for oneself. Every employee with a login holds it, including the admin
	// who will approve their own — a temple with one administrator still records their absence, and
	// the record is what the roster reads.
	REQUEST_OWN_LEAVE,

	// --- Speaking to the community ---

	// Writing to every devotee at once is the largest single act this product offers, and it is not
	// the same capability as running the kitchen — which is why it is not folded into
	// MANAGE_TEMPLE_SETTINGS or handed to kitchen staff along with the roster (E8-S2).
	MANAGE_COMMUNICATIONS,

	// --- Money ---
	MANAGE_VENDOR_PAYMENTS,
	VIEW_DONATIONS,
	MANAGE_WISHLIST,

	// --- The platform notice board (E9-S1) ---

	// Posting to every temple on the platform. Held by temple admins as well as operators, because
	// the notices that matter most — a supplier recall, a contaminated batch — are known first by
	// the temple that found them, not by whoever runs the servers.
	RAISE_PLATFORM_NOTICE,

	// Taking down somebody else's notice. The operator's alone, and the reason there is no
	// pre-moderation: a board anyone may post to needs somebody who can clear it.
	WITHDRAW_ANY_PLATFORM_NOTICE,

	// Adding to, correcting or removing from the shared recipe library (E2-S9). The operator's
	// alone: the library reaches every temple on the platform, so an edit here is not a temple's
	// own business the way its recipes are. A temple reads it and takes copies, and the copy is
	// entirely theirs from that moment.
	MANAGE_RECIPE_LIBRARY,

	// --- Overrides that carry religious or financial weight ---
	OVERRIDE_SATTVIC_ENFORCEMENT,
	OVERRIDE_CALENDAR_DATE,
}
