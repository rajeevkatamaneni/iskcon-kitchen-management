package org.iskcon.kms.error;

/**
 * Every failure a user can be shown, with the words they see and the code they can quote.
 *
 * <p>The code is the point. Temple staff hitting an error are not engineers, and whoever they
 * call for help may be a volunteer with no access to the system. "It didn't work" is not
 * diagnosable; "KMS-4172" is — it maps to one specific failure in the logs, so support can find
 * the exact event without asking anyone to describe what they saw.
 *
 * <p><strong>Codes are permanent.</strong> Once shipped, a code's number never changes and is
 * never reused, even if the message is reworded or the failure is removed. Someone may quote a
 * code from a screenshot taken a year ago, and it has to still mean the same thing.
 *
 * <p>Numbering follows the HTTP status it maps to:
 *
 * <ul>
 *   <li>4000–4099 — bad request, validation
 *   <li>4100–4199 — authentication
 *   <li>4300–4399 — authorisation
 *   <li>4400–4499 — not found
 *   <li>4900–4999 — conflict, state violations
 *   <li>5000–5099 — internal failure
 *   <li>5200–5299 — an external service let us down
 * </ul>
 */
public enum ErrorCode {

	// --- Validation ---------------------------------------------------
	VALIDATION_FAILED(4001, 400,
			"Some of the information entered isn't valid.",
			"Check the highlighted fields and try again."),

	INVALID_COORDINATES(4002, 400,
			"Those coordinates don't look right.",
			"Latitude must be between -90 and 90, longitude between -180 and 180."),

	INVALID_PHONE_NUMBER(4003, 400,
			"That phone number isn't in a format we can use.",
			"Include the country code, for example +91 98765 43210."),

	INVALID_PAN(4004, 400,
			"That PAN doesn't look right.",
			"A PAN is ten characters, like ABCDE1234F."),

	// Added for the 2026-08-20 build. Leave, staff pay, meal recording and bans each have one
	// shape a form can get wrong that the field itself cannot catch.

	LEAVE_DATES_INVALID(4005, 400,
			"Those leave dates don't work.",
			"The last day has to fall on or after the first."),

	HALF_DAY_IS_ONE_DAY(4006, 400,
			"A half day covers one date only.",
			"Choose a single date, or ask for full days across the range."),

	AMOUNT_NOT_POSITIVE(4007, 400,
			"That amount has to be more than zero.",
			"Enter what was actually paid."),

	PAYMENT_REFERENCE_REQUIRED(4008, 400,
			"A cheque or payroll payment needs its reference.",
			"Enter the cheque number or the payroll reference so this can be traced later."),

	SERVINGS_NOT_VALID(4009, 400,
			"Those servings don't look right.",
			"Enter how many were actually served, or mark the dish as not made."),

	BAN_REASON_REQUIRED(4010, 400,
			"A record like this needs both a category and your own account of it.",
			"Choose the category that fits and write what happened, in your own words."),

	// Added 2026-08-31 for the vendor review comment (V1). Dropping a supplier is a decision
	// somebody else reads months later, when they are deciding whether to bring them back.

	VENDOR_DEACTIVATION_REASON_REQUIRED(4011, 400,
			"A vendor can't be made inactive without a reason.",
			"Write why you're dropping them — whoever considers bringing them back will read it."),

	// Added 2026-08-31 for the staff review comment (STAFF1). A conduct note is permanent and
	// cannot be edited afterwards, so an empty one would sit on somebody's record for good.

	CONDUCT_NOTE_EMPTY(4012, 400,
			"A conduct note needs something written in it.",
			"Write what happened. Once saved, a note can't be changed or removed."),

	// Added 2026-08-31 for BL-9. An order line reading "3 litres of rice flour" was accepted, and
	// the delivery against it booked three thousand of something the store room counts in grams.
	// The line naming the ingredient and the two units travels with this one, because a twenty-line
	// order needs to say which line.

	INCOMPATIBLE_UNIT(4013, 400,
			"That quantity is in a unit this ingredient can't be measured in.",
			"Weight, volume and pieces don't convert into one another. Use the unit the ingredient "
					+ "is held in."),

	// Added 2026-08-31 with the editable needed-by date on a draft purchase order (E5-S3). A date
	// behind the order itself is not a request anybody can act on, and it would score the vendor
	// late the moment the order was raised (E5-S9).

	NEEDED_BY_BEFORE_ORDER_DATE(4014, 400,
			"That date is before the order was raised.",
			"Choose the day the goods are needed, on or after the order's own date."),

	// --- Authentication -----------------------------------------------
	NOT_AUTHENTICATED(4101, 401,
			"You're not signed in.",
			"Sign in and try again."),

	SESSION_EXPIRED(4102, 401,
			"Your session has expired.",
			"Sign in again to continue."),

	ACCOUNT_DISABLED(4103, 401,
			"This account has been disabled.",
			"Ask your temple administrator to restore access."),

	NO_ACCOUNT_AT_TEMPLE(4104, 401,
			"You're signed in, but you don't have an account at this temple yet.",
			"Ask your temple administrator to add you."),

	// --- Authorisation ------------------------------------------------
	NOT_PERMITTED(4301, 403,
			"You don't have permission to do that.",
			"If you think you should, ask your temple administrator."),

	CANNOT_CHANGE_OWN_ROLE(4302, 403,
			"You can't change your own role.",
			"Ask another administrator at your temple to make this change."),

	CANNOT_ASSIGN_SUPER_ADMIN(4303, 403,
			"That role can't be assigned here.",
			"Platform operator accounts are created only when a temple is set up, not from user management."),

	CANNOT_DISABLE_SELF(4304, 403,
			"You can't disable your own account.",
			"Ask another administrator at your temple to do this, so you don't lock yourself out."),

	ADJUSTMENT_REQUIRES_ADMIN(4305, 403,
			"This adjustment is large enough that a Temple Admin has to approve it.",
			"Ask a Temple Admin to make this correction, or split it into smaller ones you can explain."),

	NOT_YOUR_LEAVE_REQUEST(4306, 403,
			"That leave request isn't yours.",
			"You can only withdraw a request you made yourself."),

	NOT_THE_RAISING_TEMPLE(4307, 403,
			"Only the temple that raised this record can change it.",
			"Call them if you believe it's wrong — their name is on the record."),

	NOTICE_NOT_YOURS_TO_WITHDRAW(4308, 403,
			"Only the temple that posted this notice, or a platform operator, can take it down.",
			"If it needs taking down urgently, contact the platform operator."),

	// --- Not found ----------------------------------------------------
	TENANT_NOT_FOUND(4401, 404,
			"We couldn't find that temple.",
			"Check the address and try again."),

	RESOURCE_NOT_FOUND(4402, 404,
			"We couldn't find what you were looking for.",
			"It may have been removed."),

	NO_STAFF_RECORD(4403, 404,
			"You don't have a staff record at this temple.",
			"Leave is asked for by people the temple employs. Ask your administrator if this looks wrong."),

	// --- Conflict -----------------------------------------------------
	SLUG_ALREADY_TAKEN(4901, 409,
			"Another temple is already using that web address.",
			"Choose a different one."),

	EMAIL_ALREADY_REGISTERED(4902, 409,
			"Someone at this temple is already registered with that email address.",
			"Use a different address, or ask your administrator to check the existing account."),

	INGREDIENT_ALREADY_EXISTS(4903, 409,
			"An ingredient with that name already exists.",
			"If it's the same thing, add the new spelling as an alias; otherwise choose a distinct name."),

	// The number never changes; the words widened once it turned out recipes are only one of the
	// things that can hold an ingredient — stock movements and past orders hold it too, and those
	// are records that have to stay.
	INGREDIENT_IN_USE(4904, 409,
			"That ingredient is still in use.",
			"Remove it from the recipes, plans and orders that use it first, or keep it in the catalogue."),

	RECIPE_ALREADY_EXISTS(4905, 409,
			"A recipe with that name already exists.",
			"Choose a different name, or edit the existing recipe."),

	SATTVIC_INGREDIENT_BLOCKED(4906, 409,
			"This recipe contains an ingredient your temple treats as prohibited.",
			"Remove it, or ask a Temple Admin to save it with a reason."),

	CATEGORY_ALREADY_EXISTS(4907, 409,
			"A category with that name already exists.",
			"Choose a different name, or use the existing category."),

	MOVEMENT_ALREADY_CORRECTED(4908, 409,
			"This stock movement has already been corrected.",
			"Look at the correction that was already recorded; if that too is wrong, correct it instead."),

	INVENTORY_ITEM_ALREADY_EXISTS(4909, 409,
			"You're already tracking that ingredient in inventory.",
			"Open the existing item to adjust its stock or reorder level."),

	STOCK_WOULD_GO_NEGATIVE(4910, 409,
			"That would take the stock below zero.",
			"Check the amount against what's actually on the shelf, then adjust to the real count."),

	INSUFFICIENT_STOCK(4911, 409,
			"There isn't enough stock to cook this.",
			"Cook a smaller quantity, or receive or adjust stock for the ingredients that are short."),

	EQUIPMENT_SCRAPPED(4912, 409,
			"This item has been scrapped, so its condition can't change.",
			"Register a replacement if you've acquired one."),

	OCCASION_ALREADY_EXISTS(4913, 409,
			"An occasion with that name already exists.",
			"Choose a different name, or edit the existing occasion."),

	CANNOT_CANCEL_COOKED_MEAL(4914, 409,
			"This meal has already been cooked, so it can't be cancelled.",
			"If the stock was wrong, correct it with an inventory adjustment."),

	MEAL_PLAN_NOT_OPEN(4915, 409,
			"This meal can no longer be changed.",
			"Only a planned meal can be edited or cooked; this one is already cooked or cancelled."),

	MEAL_KIND_ALREADY_EXISTS(4916, 409,
			"That kind of meal already exists.",
			"Use the existing one, or choose a different name."),

	EKADASHI_NOT_ACKNOWLEDGED(4917, 409,
			"This recipe has grains or beans, and the day is Ekadashi.",
			"Pick an Ekadashi-friendly recipe, or confirm to cook it anyway for non-fasting visitors."),

	VENDOR_ALREADY_EXISTS(4918, 409,
			"A vendor with that name already exists.",
			"Use the existing vendor, or choose a different name."),

	// Reworded 2026-08-20 (A9). A draft is now editable in its quantities and lines, so the only
	// order this can refuse is one already out of the temple's hands — and the next step is not
	// "edit something else", it is to raise a second order for the difference.
	PO_NOT_EDITABLE(4919, 409,
			"A sent purchase order can't be changed.",
			"Raise a new one for the difference."),

	PO_INVALID_TRANSITION(4920, 409,
			"That isn't a valid step for this purchase order.",
			"Refresh to see its current status and the actions available."),

	RECEIPT_LINE_NOT_ON_PO(4921, 409,
			"One of the delivery lines doesn't belong to this purchase order.",
			"Refresh the purchase order and record the delivery against its own lines."),

	RECEIPT_LINE_EMPTY(4922, 409,
			"A delivery line must record something received or something rejected.",
			"Enter a received or rejected quantity, and give a reason for anything rejected."),

	INVOICE_DIRECT_NEEDS_DESCRIPTION(4923, 409,
			"A direct invoice with no purchase order needs a description of what was bought.",
			"Add a short description, or link the invoice to its purchase order."),

	PO_NOT_SENDABLE(4924, 409,
			"This purchase order can't be sent to a vendor.",
			"A received or cancelled order is closed; only a draft or an open sent order can go out."),

	PO_WHATSAPP_RATE_LIMITED(4925, 409,
			"This purchase order was just sent on WhatsApp.",
			"Give the vendor a moment to receive it before sending again."),

	// Was STAFF_PROFILE_ALREADY_EXISTS. Same fact, said the way E6-S8 says it: a staff profile is
	// now an employment record, so "already has a profile" is "already works here". The number is
	// untouched — somebody may be holding a screenshot of it.
	PERSON_ALREADY_EMPLOYED(4926, 409,
			"This person already works at your temple.",
			"Open their staff record to change their job or end their employment."),

	// RETIRED 4927 (was USER_NOT_KITCHEN_STAFF). Staff profiles used to demand the person already
	// hold the Kitchen Staff role, which is backwards now that hiring is what grants a role at all
	// (E6-S8). The number stays burned rather than reused: an old screenshot must never come to
	// mean something new.

	SHIFT_NOT_OPEN(4928, 409,
			"This shift has been cancelled.",
			"Cancelled shifts can't be changed or signed up for. Post a new shift instead."),

	SHIFT_ALREADY_STARTED(4929, 409,
			"This shift has already started.",
			"You can only sign up for or release a shift before it begins."),

	ALREADY_SIGNED_UP(4930, 409,
			"You're already signed up for this shift.",
			"Check My Shifts — you're on the roster."),

	SHIFT_FULL(4931, 409,
			"This shift is already full.",
			"Join the waitlist and we'll promote you automatically if a spot opens."),

	NOT_ON_SHIFT(4932, 409,
			"You're not signed up for this shift.",
			"There's nothing to release."),

	ALREADY_ON_WAITLIST(4933, 409,
			"You're already on the waitlist for this shift.",
			"We'll promote you automatically when a spot opens."),

	SHIFT_NOT_FULL(4934, 409,
			"This shift still has open spots.",
			"Sign up directly instead of joining the waitlist."),

	BROADCAST_RATE_LIMITED(4935, 409,
			"This shift has reached today's limit for update messages.",
			"To avoid overwhelming volunteers, there's a daily cap. Try again tomorrow, or ask a Temple Admin to raise the limit."),

	DONOR_80G_NOT_AVAILABLE(4936, 409,
			"This temple can't issue 80G certificates yet.",
			"You can still give — the receipt simply cannot be a tax certificate."),

	DONOR_CONSENT_REQUIRED(4937, 409,
			"Please agree to the data-use notice to continue with your details.",
			"It says what we do with your details and how long we keep them."),

	WISHLIST_ITEM_UNAVAILABLE(4938, 409,
			"This wish-list item is no longer available to sponsor.",
			"It may have just been fully sponsored. Browse the list for others still open."),

	INVOICE_OVERPAYMENT(4939, 409,
			"That payment is more than the invoice's outstanding balance.",
			"Enter an amount up to what's still due."),

	INVOICE_ALREADY_PAID(4940, 409,
			"This invoice is already fully paid.",
			"There's nothing left to record against it."),

	MEAL_KIND_UNKNOWN(4942, 409,
			"This temple doesn't have that kind of meal.",
			"Choose one from the list, or ask a Temple Admin to add it in temple settings."),

	READY_BY_TIME_REQUIRED(4943, 409,
			"This kind of meal needs the time it has to be ready.",
			"Enter the time the food must be ready. Everyday meals suggest one; occasional meals always ask."),

	MEAL_CLIENT_REQUIRED(4944, 409,
			"This kind of meal is cooked for someone, so it needs their name.",
			"Enter who asked for it, and where it's going."),

	MEAL_VENUE_REQUIRED(4945, 409,
			"This food leaves the temple, so it needs a destination.",
			"Enter where it's going."),

	/**
	 * A meal that is cooking something has to say how many people it is cooking for. Everything the
	 * plan is worth — how much of each preparation to make, what it will cost, what a serving of it
	 * costs — is worked out from that number, and the application used to supply 100 of its own when
	 * nobody had said. A meal with nothing in it yet is fine: nobody has said what or for how many.
	 */
	MEAL_HEAD_COUNT_REQUIRED(4989, 409,
			"This meal has something being cooked, so it needs to know how many people are expected.",
			"Enter how many adults, children or seniors are coming. Every preparation is worked out from that number."),

	EXPORT_REQUIRED_BEFORE_DELETE(4941, 409,
			"Take a data export before deleting this temple.",
			"Download the temple's data export, then delete. Deleting erases everything permanently, and the export is the only copy."),

	PAYMENT_CREDENTIALS_REJECTED(4946, 409,
			"Your payment provider didn't accept those details.",
			"Check the key ID and secret against your provider's dashboard and try again. Nothing has been saved."),

	PAYMENT_PROVIDER_UNSUPPORTED(4947, 409,
			"We can't collect donations through that provider yet.",
			"Choose one of the providers offered, or ask us to add yours."),

	PAYMENT_NOT_CONFIGURED(4948, 409,
			"This temple hasn't set up a payment gateway yet.",
			"Add your provider's key ID and secret under Settings, then try again."),

	EMPLOYMENT_ALREADY_ENDED(4949, 409,
			"This person no longer works at your temple.",
			"A past employment record can be read but not changed. Hire them again to bring them back."),

	COMMUNICATION_ALREADY_SENT(4951, 409,
			"This message has already gone out.",
			"A sent message can't be changed or sent again. Write a new one if you need to say more."),

	COMMUNICATION_HAS_NO_AUDIENCE(4952, 409,
			"Nobody would receive this message.",
			"Everyone has either not agreed to be contacted or has turned off this kind of message. Try a different kind, or check your devotee list."),

	STAFF_ACCESS_NEEDS_CONTACT(4950, 409,
			"Someone can only be given a sign-in if we have both their email address and their phone number.",
			"Add the missing one, or hire them without app access."),

	// --- Conflicts added for the 2026-08-20 build ----------------------
	//
	// Numbered in the order the build works through them: leave, then staff pay, then meal
	// recording, then bans, then the notice board. Bands are deliberate — a gap is cheaper than a
	// renumber, and codes are permanent.

	// Leave (B7)
	LEAVE_OVERLAPS_EXISTING(4953, 409,
			"This person already has leave recorded across some of those dates.",
			"Open their leave and change the existing record, or choose dates that don't overlap."),

	LEAVE_ALREADY_DECIDED(4954, 409,
			"That request has already been answered.",
			"An approved request can be revoked; a declined one can't be answered twice."),

	LEAVE_NOT_APPROVED(4955, 409,
			"Only approved leave can be revoked.",
			"A request still waiting can be declined instead."),

	CANNOT_SCHEDULE_OVER_LEAVE(4956, 409,
			"This person is on approved leave that day.",
			"Revoke the leave first if they are in after all."),

	SWAP_NEEDS_TWO_DAYS(4957, 409,
			"A swap needs two different days.",
			"Pick the day they'll work instead."),

	// Staff pay (B8)
	DEDUCTIONS_EXCEED_GROSS(4958, 409,
			"Those deductions come to more than the payment itself.",
			"Recover less this time; the rest of the advance stays outstanding."),

	DEDUCTION_EXCEEDS_ADVANCE(4959, 409,
			"That's more than is still outstanding on the advance.",
			"Recover what's left of it, or choose a different advance."),

	ADVANCE_ALREADY_RECOVERED(4960, 409,
			"That advance has already been recovered in full.",
			"There's nothing left on it to deduct."),

	// Reworded 2026-08-20. It used to refuse a payment with deductions and tell the reader to
	// "void the deductions first" — a door that does not exist, which made a mistyped docked
	// salary permanent. Voiding a payment now voids its deductions with it, so the only thing this
	// still refuses is striking an advance somebody has actually been docked for, and it says so.
	STAFF_PAYMENT_NOT_VOIDABLE(4961, 409,
			"Money has already been recovered against this advance.",
			"Void the payment that recovered it first, and this advance can then be struck."),

	// Meal recording and the job card (B4, B5)
	MEAL_ALREADY_RECORDED(4962, 409,
			"This meal has already been recorded.",
			"What was cooked can't be changed afterwards. Ask a Temple Admin if the figures are wrong."),

	MEAL_NOT_RECORDABLE(4963, 409,
			"This meal can't be recorded.",
			"A cancelled meal never went to the kitchen, so there is nothing to record against it."),

	// Bans and the check at hire (B9)
	BAN_ALREADY_EXISTS(4964, 409,
			"Your temple has already recorded this against that person.",
			"Open the existing record to update or retract it."),

	BAN_ALREADY_RETRACTED(4965, 409,
			"That record has already been retracted.",
			"A retracted record stays on file but no longer shows at a hire."),

	// Recipes
	RECIPE_IN_USE(4967, 409,
			"This recipe has been cooked, so it can't be deleted.",
			"Archive it instead — it will stop appearing when you plan a meal, and the record of what was cooked stays intact."),

	// The shared recipe library (E2-S12)
	RECIPE_ALREADY_ADDED(4968, 409,
			"You already have this recipe.",
			"Open it from your list to change your copy."),

	RECIPE_NEEDS_PROHIBITED_INGREDIENT(4970, 409,
			"This recipe needs an ingredient your temple doesn't allow.",
			"Nothing was added. Write your own version without it, or ask an admin about the ingredient."),

	MASTER_RECIPE_NOT_FOUND(4971, 404,
			"That recipe is no longer in the shared library.",
			"Search again — it may have been renamed or taken down."),

	// Kitchens, and asking the store for ingredients (E10)
	KITCHEN_NAME_TAKEN(4972, 409,
			"Your temple already has a kitchen with that name.",
			"Pick a name that tells them apart, like the part of the temple it serves."),

	KITCHEN_IN_USE(4973, 409,
			"This kitchen has asked for ingredients before, so it can't be removed.",
			"Archive it instead — it stops appearing in the lists and its history stays readable."),

	KITCHEN_NOT_FOUND(4974, 404,
			"We couldn't find that kitchen.",
			"It may have been archived. Open the kitchens list and pick from there."),

	KITCHEN_ARCHIVED(4975, 409,
			"That kitchen has been archived.",
			"Restore it from the kitchens list first, or pick a different one."),

	KITCHEN_PLANS_ITS_OWN_MEALS(4976, 409,
			"This kitchen plans its meals here, so its ingredients are drawn when a meal is recorded.",
			"Pick a kitchen that only asks for ingredients, or turn the meal planner off for this one."),

	INGREDIENT_REQUEST_NOT_FOUND(4977, 404,
			"We couldn't find that request.",
			"Open the ingredient requests list and pick from there."),

	NOT_YOUR_INGREDIENT_REQUEST(4978, 403,
			"This request belongs to somebody else.",
			"You can read it, but only the person who wrote it can change it."),

	INGREDIENT_REQUEST_NOT_EDITABLE(4979, 409,
			"This request can no longer be changed.",
			"Raise a new request for anything else the kitchen needs."),

	INGREDIENT_REQUEST_ALREADY_DECIDED(4980, 409,
			"Somebody has already answered this request.",
			"Open it to see the answer and who gave it."),

	INGREDIENT_REQUEST_NOT_APPROVED(4981, 409,
			"This request hasn't been approved yet.",
			"It has to be approved before the store can issue against it."),

	INGREDIENT_REQUEST_ALREADY_ISSUED(4982, 409,
			"The store has already issued against this request.",
			"Raise a new request if the kitchen needs more."),

	INGREDIENT_REQUEST_EMPTY(4983, 409,
			"This request doesn't ask for anything yet.",
			"Add at least one ingredient before sending it for review."),

	INSUFFICIENT_STOCK_TO_ISSUE(4987, 409,
			"The store doesn't hold enough of everything on this request.",
			"Nothing was issued. Count the shelf and correct the stock, or issue a smaller amount of what is short."),

	INGREDIENT_REQUEST_NOT_SUBMITTED(4986, 409,
			"This request hasn't been sent for review yet.",
			"Open it and send it for review, then it can be approved or turned down."),

	KITCHEN_MAIN_MOVED(4985, 409,
			"Somebody else changed your temple's main kitchen a moment ago.",
			"Open the kitchens list to see which one holds it now, then set it again if you still want to."),

	INGREDIENT_REQUEST_NEEDS_DISHES(4984, 409,
			"Say what the kitchen is cooking before sending this for review.",
			"List each dish and how much of it, so whoever reviews this can judge the amounts."),

	// Cost per serving by meal kind (E3-S9). The period is asked for on the screen rather than
	// typed into a field, so getting it wrong takes two mis-clicks — but the report walks every
	// dish planned in the range, and an unbounded one would be a slow page rather than an answer.
	COST_PERIOD_NOT_VALID(4988, 400,
			"That period doesn't work.",
			"The last day has to fall on or after the first, and the period can cover at most a year."),

	// The notice board (E9-S1)
	NOTICE_ALREADY_WITHDRAWN(4966, 409,
			"This notice has already been withdrawn.",
			"Everyone who saw it has been shown the withdrawal."),

	// --- Internal -----------------------------------------------------
	UNEXPECTED_FAILURE(5001, 500,
			"Something went wrong at our end.",
			"Try again in a moment. If it keeps happening, quote the code below."),

	// --- External services --------------------------------------------
	WHATSAPP_SEND_FAILED(5201, 502,
			"We couldn't send that message on WhatsApp.",
			"The number may be wrong or unreachable. You can download the document and share it manually."),

	TRANSLATION_FAILED(5202, 502,
			"We couldn't translate this right now.",
			"The English version is still available. Try translating again shortly."),

	DOCUMENT_GENERATION_FAILED(5203, 502,
			"We couldn't produce that document.",
			"Try again in a moment."),

	PAYMENT_GATEWAY_ERROR(5204, 502,
			"We couldn't reach the payment provider just now.",
			"Please try again in a moment; you haven't been charged.");

	private final int number;
	private final int httpStatus;
	private final String whatHappened;
	private final String whatToDo;

	ErrorCode(int number, int httpStatus, String whatHappened, String whatToDo) {
		this.number = number;
		this.httpStatus = httpStatus;
		this.whatHappened = whatHappened;
		this.whatToDo = whatToDo;
	}

	/** The reference shown to the user and quoted to support, e.g. {@code KMS-4172}. */
	public String reference() {
		return "KMS-" + number;
	}

	public int number() {
		return number;
	}

	public int httpStatus() {
		return httpStatus;
	}

	/** Plain language, no blame, no jargon. */
	public String whatHappened() {
		return whatHappened;
	}

	/** What the person can actually do next. Never "contact support" as the only option. */
	public String whatToDo() {
		return whatToDo;
	}
}
