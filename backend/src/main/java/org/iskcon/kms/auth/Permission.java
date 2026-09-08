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

	// Setting which ingredients are Ekadashi-prohibited is a religious-policy decision, held apart
	// from ordinary recipe/ingredient editing so that a Kitchen Staff member who may add
	// ingredients still cannot decide what is prohibited (E4-S6).
	//
	// The name is historical. It was minted for the sattvic-prohibited flag (E2-S1) and gated both
	// flags; D-18 deleted the sattvic flag on 2026-09-08 and this constant survives it because the
	// Ekadashi flag is what it now guards — `/ingredients/{id}/ekadashi-flag` and the create-time
	// check in IngredientService. Renaming it would rewrite an authority string across the
	// @PreAuthorize sites and is not what D-18 ruled, so it is left as it is and said out loud
	// here instead. Worth folding into the comprehensive permissions review D-11 records.
	MANAGE_SATTVIC_POLICY,

	// Kitchen staff make routine stock adjustments, but a large one (over 20% of what's on hand)
	// needs a Temple Admin to approve it — a big write-off is a leadership call, and the split
	// makes an unusual correction visible rather than routine (E3-S7).
	APPROVE_LARGE_STOCK_ADJUSTMENT,

	// Recording that a machine was serviced, deciding how often it must be, and keeping the list of
	// firms that do it (E3-S10 D10). Held apart from MANAGE_INVENTORY because finding a broken
	// grinder and scheduling its maintenance are different jobs done by different people: kitchen
	// staff are the ones standing in front of it when it stops, and they keep registering equipment
	// and moving its condition. Booking the engineer, agreeing the interval and reading the overdue
	// count is the administrator's. Same gravity split as APPROVE_LARGE_STOCK_ADJUSTMENT and
	// MANAGE_SATTVIC_POLICY — and it is what makes "this belongs on the Temple Admin's dashboard"
	// enforceable, rather than a matter of which screen a role happens to land on.
	MANAGE_EQUIPMENT_SERVICING,

	// Bringing a scrapped machine back into use (D-15). Split out from MANAGE_INVENTORY, which
	// everyone who runs the kitchen holds and which is what scraps a machine in the first place.
	// Undoing a disposal is a different kind of act from recording one: the register hides scrapped
	// items by default, so somebody reinstating one has gone looking for a thing the temple has
	// already written off. Rajeev ruled it Temple Admin alone on 2026-09-07, by the same reasoning
	// D-4 gave for VOID_DONATION — widening later is one line, and narrowing after temples have
	// built a habit is a conversation with every one of them.
	REINSTATE_SCRAPPED_EQUIPMENT,

	// Which kitchens a temple runs is a structural fact about the temple, like its settings, and it
	// is held apart from daily kitchen work for that reason: a Kitchen Manager runs a kitchen, and
	// deciding that another one exists is not part of running it (E10-S2).
	MANAGE_KITCHENS,

	// Any staff member may ask the store for ingredients — a cook who has run out of ghee should
	// not have to find an admin to say so (E10-S5).
	REQUEST_INGREDIENTS,

	// Answering a request is held apart from raising one, or the asking would be its own approval.
	// The temple's storekeeper is a Kitchen Manager: this system has no Storekeeper role and
	// deliberately does not add one, because a job title that maps cleanly onto permissions we
	// already have is not a role (BACKLOG BL-3, and E10 design D4).
	APPROVE_INGREDIENT_REQUESTS,

	// Held apart from approving, because they are different acts by different people at different
	// times: approving decides that the kitchen may have it, issuing records that it physically
	// left the shelf and draws the stock down (E10-S7).
	ISSUE_INGREDIENTS,

	// --- Ordering ---
	MANAGE_VENDORS,
	MANAGE_PURCHASE_ORDERS,

	// --- Workforce ---

	// Hiring, employing and letting go — held apart from MANAGE_STAFF_SCHEDULE because it is a
	// different kind of act: it is the only door into the temple's own roles (a hire may be granted
	// Temple Admin), and it holds a person's date of birth, address and PAN. Editing next week's
	// hours is routine; deciding who works here is not (E6-S8).
	MANAGE_STAFF,

	// Reading and writing the dated conduct notes on somebody's employment record (E6-S16). Its own
	// permission because the reading is the danger, not the writing: everything else on a profile is
	// held behind MANAGE_STAFF, and folding conduct in there would mean the first thing that happens
	// is a kitchen manager reading their colleague's warning. There is direct precedent — E6-S8 D9
	// split MANAGE_STAFF from MANAGE_STAFF_SCHEDULE so that giving somebody the roster did not hand
	// them everyone's date of birth and PAN. This is the same argument one step further in: a note
	// about how a person behaved is the most personal thing the employment record holds, and who may
	// read it should be decidable on its own rather than arriving with the hiring paperwork.
	MANAGE_STAFF_CONDUCT_NOTES,

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
	//
	// OVERRIDE_SATTVIC_ENFORCEMENT stood here until 2026-09-08. D-18 deleted the block it was the
	// single escape from, so it granted a Temple Admin permission to step past nothing. Unlike an
	// error code, a permission constant carries no permanent number and nothing quotes it from a
	// screenshot, so it is simply gone rather than retired in place.
	OVERRIDE_CALENDAR_DATE,
}
