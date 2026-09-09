package org.iskcon.kms.error;

/**
 * Every failure a user can be shown, with the words they see and the code they can quote.
 *
 * <p>The code is the point. Temple staff hitting an error are not engineers, and whoever they
 * call for help may be a volunteer with no access to the system. "It didn't work" is not
 * diagnosable; "KMS-500002" is — it maps to one specific failure in the logs, so support can find
 * the exact event without asking anyone to describe what they saw.
 *
 * <p><strong>Codes are permanent.</strong> Once shipped, a code's number never changes and is
 * never reused, even if the message is reworded or the failure is removed. Someone may quote a
 * code from a screenshot taken a year ago, and it has to still mean the same thing.
 *
 * <p>Numbering is six digits, and only the first digit carries meaning — the family, which is the
 * HTTP status family the code maps to:
 *
 * <ul>
 *   <li><strong>400001</strong> upward — the request was wrong: validation, authentication,
 *       authorisation, not found, conflict. Anything answered with an HTTP 4xx.
 *   <li><strong>500001</strong> upward — we were wrong, or somebody we depend on was. Anything
 *       answered with an HTTP 5xx.
 * </ul>
 *
 * <p><strong>The hundreds no longer mean anything.</strong> Codes are allocated in the order they
 * are declared below and nothing else; a new code takes the next free number in its family. The
 * old four-digit scheme banded them by the kind of 4xx — 4000s validation, 4100s authentication,
 * 4300s authorisation, 4400s not found, 4900s conflict — and that band is deliberately gone. It
 * duplicated the {@code httpStatus} sitting beside it in every constant, so the two could disagree
 * and one of them had to be the liar; and it did not survive contact with the product anyway —
 * 92 of the 128 codes had crowded into a 4900s conflict band with room for 99, which was already
 * spilling into whatever number happened to be free. Read the family from the first digit and the
 * status from {@code httpStatus}. Read nothing at all from the rest.
 *
 * <p><strong>Six digits, not four, on purpose.</strong> Every code was renumbered once, on
 * 2026-09-07, and going to six digits is what made that safe: no four-digit code is a valid
 * six-digit one, so the two namespaces cannot overlap and no number ever means two different
 * things. A four-digit code quoted off an old screenshot, out of git history or out of Rajeev's
 * review notes is unambiguously an old-scheme code, and
 * {@code docs/ERROR-CODE-RENUMBER-2026-09-07.md} holds the full old-to-new table that resolves it.
 * That is the last renumber; the permanence rule above governs from here.
 */
public enum ErrorCode {

	// --- Validation ---------------------------------------------------
	VALIDATION_FAILED(400001, 400,
			"Some of the information entered isn't valid.",
			"Check the highlighted fields and try again."),

	INVALID_COORDINATES(400002, 400,
			"Those coordinates don't look right.",
			"Latitude must be between -90 and 90, longitude between -180 and 180."),

	INVALID_PHONE_NUMBER(400003, 400,
			"That phone number isn't in a format we can use.",
			"Include the country code, for example +91 98765 43210."),

	INVALID_PAN(400004, 400,
			"That PAN doesn't look right.",
			"A PAN is ten characters, like ABCDE1234F."),

	// Added for the 2026-08-20 build. Leave, staff pay, meal recording and bans each have one
	// shape a form can get wrong that the field itself cannot catch.

	LEAVE_DATES_INVALID(400005, 400,
			"Those leave dates don't work.",
			"The last day has to fall on or after the first."),

	HALF_DAY_IS_ONE_DAY(400006, 400,
			"A half day covers one date only.",
			"Choose a single date, or ask for full days across the range."),

	AMOUNT_NOT_POSITIVE(400007, 400,
			"That amount has to be more than zero.",
			"Enter what was actually paid."),

	PAYMENT_REFERENCE_REQUIRED(400008, 400,
			"A cheque or payroll payment needs its reference.",
			"Enter the cheque number or the payroll reference so this can be traced later."),

	SERVINGS_NOT_VALID(400009, 400,
			"Those servings don't look right.",
			"Enter how many were actually served, or mark the dish as not made."),

	BAN_REASON_REQUIRED(400010, 400,
			"A record like this needs both a category and your own account of it.",
			"Choose the category that fits and write what happened, in your own words."),

	// Added 2026-08-31 for the vendor review comment (V1). Dropping a supplier is a decision
	// somebody else reads months later, when they are deciding whether to bring them back.

	VENDOR_DEACTIVATION_REASON_REQUIRED(400011, 400,
			"A vendor can't be made inactive without a reason.",
			"Write why you're dropping them — whoever considers bringing them back will read it."),

	// Added 2026-08-31 for the staff review comment (STAFF1). A conduct note is permanent and
	// cannot be edited afterwards, so an empty one would sit on somebody's record for good.

	CONDUCT_NOTE_EMPTY(400012, 400,
			"A conduct note needs something written in it.",
			"Write what happened. Once saved, a note can't be changed or removed."),

	// Added 2026-08-31 for BL-9. An order line reading "3 litres of rice flour" was accepted, and
	// the delivery against it booked three thousand of something the store room counts in grams.
	// The line naming the ingredient and the two units travels with this one, because a twenty-line
	// order needs to say which line.

	INCOMPATIBLE_UNIT(400013, 400,
			"That quantity is in a unit this ingredient can't be measured in.",
			"Weight, volume and pieces don't convert into one another. Use the unit the ingredient "
					+ "is held in."),

	// Added 2026-08-31 with the editable needed-by date on a draft purchase order (E5-S3). A date
	// behind the order itself is not a request anybody can act on, and it would score the vendor
	// late the moment the order was raised (E5-S9).

	NEEDED_BY_BEFORE_ORDER_DATE(400014, 400,
			"That date is before the order was raised.",
			"Choose the day the goods are needed, on or after the order's own date."),

	// Equipment servicing (E3-S10). Two things a person can tell the register that cannot be true.
	//
	// The first returns 409 and sits between two 400s, which under the old banded numbering was an
	// anomaly worth a paragraph of explanation. It is not one any more: numbers run in declaration
	// order within the family and say nothing about which 4xx is coming back. The HTTP status is
	// the field beside the number, and it always was.

	EQUIPMENT_SERIAL_ALREADY_USED(400015, 409,
			"Another piece of equipment already has that serial number.",
			"Check the number against the plate on the machine. If it matches, the machine is "
					+ "already in the register — open it rather than adding it again."),

	// A service recorded for next Tuesday has not happened. "Future" is measured against the
	// temple's own day, not the server's, which is why this is refused in the service rather than
	// by an annotation on the request.

	SERVICE_DATE_IN_FUTURE(400016, 400,
			"A service can't be recorded before it has happened.",
			"Enter the day the work was actually done. Book a future visit in your own diary; "
					+ "record it here once the engineer has been."),

	// A constant stood here until 2026-09-04: SERVICE_PROVIDER_IN_USE, old-scheme KMS-4017. It is
	// gone rather than retired. The rule that codes are never reused or renumbered protects
	// somebody quoting one off an old screenshot, and there was nobody to protect: the managed
	// service-provider list was reversed (V90) before it was ever deployed, so no user ever saw
	// that number and no screenshot carries it. It leaves no hole here — the sequence is dense and
	// allocated in declaration order — and KMS-4017 stays retired in the old namespace, which the
	// six-digit scheme keeps permanently separate from this one.

	// --- Authentication -----------------------------------------------
	NOT_AUTHENTICATED(400017, 401,
			"You're not signed in.",
			"Sign in and try again."),

	SESSION_EXPIRED(400018, 401,
			"Your session has expired.",
			"Sign in again to continue."),

	ACCOUNT_DISABLED(400019, 401,
			"This account has been disabled.",
			"Ask your temple administrator to restore access."),

	NO_ACCOUNT_AT_TEMPLE(400020, 401,
			"You're signed in, but you don't have an account at this temple yet.",
			"Ask your temple administrator to add you."),

	// --- Authorisation ------------------------------------------------
	NOT_PERMITTED(400021, 403,
			"You don't have permission to do that.",
			"If you think you should, ask your temple administrator."),

	CANNOT_CHANGE_OWN_ROLE(400022, 403,
			"You can't change your own role.",
			"Ask another administrator at your temple to make this change."),

	// KMS-400023 was CANNOT_ASSIGN_SUPER_ADMIN and is retired, never to be reallocated (T-040).
	// It was thrown by exactly one guard, in RoleChangeService, and that service was deleted as dead
	// code: its endpoint had no caller anywhere in the product. Applying D-9's precedent rather than
	// making a new decision — that ruling deleted SESSION_EXPIRED and retired KMS-400018 on the same
	// reasoning, which is that a code nothing can raise is a lie in a catalogue whose whole value is
	// that a number means one thing for ever. The asymmetry decides it: retiring costs a fresh number
	// if the capability ever returns, and numbers are never reused anyway; keeping it costs a
	// permanent entry no code path can produce, which is what somebody quotes off a screenshot and
	// cannot be helped with.
	//
	// The protection did not go with the code. staff/SystemAccess.java has three constants and cannot
	// express SUPER_ADMIN, so the only remaining role-assigning path cannot represent the value this
	// refused. It is now structural rather than checked, which is stronger and worth knowing.
	//
	// Recorded alongside KMS-400018 in docs/ERROR-CODE-RENUMBER-2026-09-07.md.

	CANNOT_DISABLE_SELF(400024, 403,
			"You can't disable your own account.",
			"Ask another administrator at your temple to do this, so you don't lock yourself out."),

	ADJUSTMENT_REQUIRES_ADMIN(400025, 403,
			"This adjustment is large enough that a Temple Admin has to approve it.",
			"Ask a Temple Admin to make this correction, or split it into smaller ones you can explain."),

	NOT_YOUR_LEAVE_REQUEST(400026, 403,
			"That leave request isn't yours.",
			"You can only withdraw a request you made yourself."),

	NOT_THE_RAISING_TEMPLE(400027, 403,
			"Only the temple that raised this record can change it.",
			"Call them if you believe it's wrong — their name is on the record."),

	NOTICE_NOT_YOURS_TO_WITHDRAW(400028, 403,
			"Only the temple that posted this notice, or a platform operator, can take it down.",
			"If it needs taking down urgently, contact the platform operator."),

	// --- Not found ----------------------------------------------------
	TENANT_NOT_FOUND(400029, 404,
			"We couldn't find that temple.",
			"Check the address and try again."),

	RESOURCE_NOT_FOUND(400030, 404,
			"We couldn't find what you were looking for.",
			"It may have been removed."),

	NO_STAFF_RECORD(400031, 404,
			"You don't have a staff record at this temple.",
			"Leave is asked for by people the temple employs. Ask your administrator if this looks wrong."),

	// --- Conflict -----------------------------------------------------
	SLUG_ALREADY_TAKEN(400032, 409,
			"Another temple is already using that web address.",
			"Choose a different one."),

	EMAIL_ALREADY_REGISTERED(400033, 409,
			"Someone at this temple is already registered with that email address.",
			"Use a different address, or ask your administrator to check the existing account."),

	INGREDIENT_ALREADY_EXISTS(400034, 409,
			"An ingredient with that name already exists.",
			"If it's the same thing, add the new spelling as an alias; otherwise choose a distinct name."),

	// The number never changes; the words widened once it turned out recipes are only one of the
	// things that can hold an ingredient — stock movements and past orders hold it too, and those
	// are records that have to stay.
	INGREDIENT_IN_USE(400035, 409,
			"That ingredient is still in use.",
			"Remove it from the recipes, plans and orders that use it first, or keep it in the catalogue."),

	RECIPE_ALREADY_EXISTS(400036, 409,
			"A recipe with that name already exists.",
			"Choose a different name, or edit the existing recipe."),

	// 400037 was SATTVIC_INGREDIENT_BLOCKED. Retired 2026-09-08 by D-18, which deleted the
	// sattvic-prohibited flag outright: with no ingredient able to carry the flag, nothing could
	// ever throw this again, and a declared code that is never thrown is worse than no code — the
	// next reader of this file believes the app says something it does not. Same reasoning D-9 gave
	// for retiring 400018. The number is never reallocated. See ERROR-CODE-RENUMBER-2026-09-07.md.

	CATEGORY_ALREADY_EXISTS(400038, 409,
			"A category with that name already exists.",
			"Choose a different name, or use the existing category."),

	MOVEMENT_ALREADY_CORRECTED(400039, 409,
			"This stock movement has already been corrected.",
			"Look at the correction that was already recorded; if that too is wrong, correct it instead."),

	INVENTORY_ITEM_ALREADY_EXISTS(400040, 409,
			"You're already tracking that ingredient in inventory.",
			"Open the existing item to adjust its stock or reorder level."),

	STOCK_WOULD_GO_NEGATIVE(400041, 409,
			"That would take the stock below zero.",
			"Check the amount against what's actually on the shelf, then adjust to the real count."),

	INSUFFICIENT_STOCK(400042, 409,
			"There isn't enough stock to cook this.",
			"Cook a smaller quantity, or receive or adjust stock for the ingredients that are short."),

	EQUIPMENT_SCRAPPED(400043, 409,
			"This item has been scrapped, so its condition can't change.",
			"Register a replacement, or reinstate this item if it's back in use."),

	OCCASION_ALREADY_EXISTS(400044, 409,
			"An occasion with that name already exists.",
			"Choose a different name, or edit the existing occasion."),

	CANNOT_CANCEL_COOKED_MEAL(400045, 409,
			"This meal has already been cooked, so it can't be cancelled.",
			"If the stock was wrong, correct it with an inventory adjustment."),

	MEAL_PLAN_NOT_OPEN(400046, 409,
			"This meal can no longer be changed.",
			"Only a planned meal can be edited or cooked; this one is already cooked or cancelled."),

	MEAL_KIND_ALREADY_EXISTS(400047, 409,
			"That kind of meal already exists.",
			"Use the existing one, or choose a different name."),

	EKADASHI_NOT_ACKNOWLEDGED(400048, 409,
			"This recipe has grains or beans, and the day is Ekadashi.",
			"Pick an Ekadashi-friendly recipe, or confirm to cook it anyway for non-fasting visitors."),

	VENDOR_ALREADY_EXISTS(400049, 409,
			"A vendor with that name already exists.",
			"Use the existing vendor, or choose a different name."),

	// Reworded 2026-08-20 (A9). A draft is now editable in its quantities and lines, so the only
	// order this can refuse is one already out of the temple's hands — and the next step is not
	// "edit something else", it is to raise a second order for the difference.
	PO_NOT_EDITABLE(400050, 409,
			"A sent purchase order can't be changed.",
			"Raise a new one for the difference."),

	PO_INVALID_TRANSITION(400051, 409,
			"That isn't a valid step for this purchase order.",
			"Refresh to see its current status and the actions available."),

	RECEIPT_LINE_NOT_ON_PO(400052, 409,
			"One of the delivery lines doesn't belong to this purchase order.",
			"Refresh the purchase order and record the delivery against its own lines."),

	RECEIPT_LINE_EMPTY(400053, 409,
			"A delivery line must record something received or something rejected.",
			"Enter a received or rejected quantity, and give a reason for anything rejected."),

	INVOICE_DIRECT_NEEDS_DESCRIPTION(400054, 409,
			"A direct invoice with no purchase order needs a description of what was bought.",
			"Add a short description, or link the invoice to its purchase order."),

	PO_NOT_SENDABLE(400055, 409,
			"This purchase order can't be sent to a vendor.",
			"A received or cancelled order is closed; only a draft or an open sent order can go out."),

	PO_WHATSAPP_RATE_LIMITED(400056, 409,
			"This purchase order was just sent on WhatsApp.",
			"Give the vendor a moment to receive it before sending again."),

	// Was STAFF_PROFILE_ALREADY_EXISTS. Same fact, said the way E6-S8 says it: a staff profile is
	// now an employment record, so "already has a profile" is "already works here". Renaming the
	// constant did not disturb the number it carried at the time — old-scheme KMS-4926 — because
	// somebody may be holding a screenshot of it. The 2026-09-07 renumber moved every number at
	// once, and the mapping document is what a screenshot of either vintage is resolved against.
	PERSON_ALREADY_EMPLOYED(400057, 409,
			"This person already works at your temple.",
			"Open their staff record to change their job or end their employment."),

	// RETIRED: old-scheme KMS-4927, USER_NOT_KITCHEN_STAFF. Staff profiles used to demand the
	// person already hold the Kitchen Staff role, which is backwards now that hiring is what grants
	// a role at all (E6-S8). It shipped, so its number stays burned rather than reused: an old
	// screenshot must never come to mean something new. It burns a number in the old four-digit
	// namespace only — nothing here inherits it, and no six-digit code corresponds to it.

	SHIFT_NOT_OPEN(400058, 409,
			"This shift has been cancelled.",
			"Cancelled shifts can't be changed or signed up for. Post a new shift instead."),

	SHIFT_ALREADY_STARTED(400059, 409,
			"This shift has already started.",
			"You can only sign up for or release a shift before it begins."),

	ALREADY_SIGNED_UP(400060, 409,
			"You're already signed up for this shift.",
			"Check My Shifts — you're on the roster."),

	SHIFT_FULL(400061, 409,
			"This shift is already full.",
			"Join the waitlist and we'll promote you automatically if a spot opens."),

	NOT_ON_SHIFT(400062, 409,
			"You're not signed up for this shift.",
			"There's nothing to release."),

	ALREADY_ON_WAITLIST(400063, 409,
			"You're already on the waitlist for this shift.",
			"We'll promote you automatically when a spot opens."),

	SHIFT_NOT_FULL(400064, 409,
			"This shift still has open spots.",
			"Sign up directly instead of joining the waitlist."),

	BROADCAST_RATE_LIMITED(400065, 409,
			"This shift has reached today's limit for update messages.",
			"To avoid overwhelming volunteers, there's a daily cap. Try again tomorrow, or ask a Temple Admin to raise the limit."),

	DONOR_80G_NOT_AVAILABLE(400066, 409,
			"This temple can't issue 80G certificates yet.",
			"You can still give — the receipt simply cannot be a tax certificate."),

	DONOR_CONSENT_REQUIRED(400067, 409,
			"Please agree to the data-use notice to continue with your details.",
			"It says what we do with your details and how long we keep them."),

	WISHLIST_ITEM_UNAVAILABLE(400068, 409,
			"This wish-list item is no longer available to sponsor.",
			"It may have just been fully sponsored. Browse the list for others still open."),

	INVOICE_OVERPAYMENT(400069, 409,
			"That payment is more than the invoice's outstanding balance.",
			"Enter an amount up to what's still due."),

	INVOICE_ALREADY_PAID(400070, 409,
			"This invoice is already fully paid.",
			"There's nothing left to record against it."),

	MEAL_KIND_UNKNOWN(400071, 409,
			"This temple doesn't have that kind of meal.",
			"Choose one from the list, or ask a Temple Admin to add it in temple settings."),

	READY_BY_TIME_REQUIRED(400072, 409,
			"This kind of meal needs the time it has to be ready.",
			"Enter the time the food must be ready. Everyday meals suggest one; occasional meals always ask."),

	/**
	 * <strong>Retired, not reused (E4-S15).</strong> This was the catering refusal, and the
	 * *Catering order* kind it belonged to no longer exists — an outside event now refuses with
	 * {@link #EVENT_CONTACT_REQUIRED} instead. The constant stays exactly where it is, with its
	 * number and its words untouched, because somebody may still quote it from a screenshot taken a
	 * year ago and it has to still mean what it meant then. Nothing new is ever attached to it.
	 */
	MEAL_CLIENT_REQUIRED(400073, 409,
			"This kind of meal is cooked for someone, so it needs their name.",
			"Enter who asked for it, and where it's going."),

	/**
	 * <strong>Retired, not reused (E4-S15).</strong> The venue refusal, for the same reason as
	 * {@link #MEAL_CLIENT_REQUIRED}: a delivered event now refuses with
	 * {@link #EVENT_DELIVERY_DETAILS_REQUIRED}. Kept for the screenshots.
	 */
	MEAL_VENUE_REQUIRED(400074, 409,
			"This food leaves the temple, so it needs a destination.",
			"Enter where it's going."),

	// --- Events (E4-S15) ----------------------------------------------
	//
	// These four sat at old-scheme 4990-4993, well away from their neighbours: they were drafted as
	// 4946-4949, which turned out to belong to the payment and employment paths already, and were
	// moved before anything shipped. A number is only free once. The gap they left is not a feature
	// of the new numbering — allocation is by declaration order now, so they read as a run.

	EVENT_NAME_REQUIRED(400075, 409,
			"An event needs a name.",
			"Give it the name people would call it — \"Children's Bhagavad-gita Reading\". It is how you will find it again."),

	EVENT_CONTACT_REQUIRED(400076, 409,
			"Food going outside the temple needs somebody to contact.",
			"Enter the contact's name and phone number. Both: a contact you can't ring isn't a contact."),

	EVENT_DELIVERY_DETAILS_REQUIRED(400077, 409,
			"A delivery needs an address and the time the guests eat.",
			"Enter where the food is going and when the guests sit down, so we can say when to leave."),

	/**
	 * Told to the planner, and never a refusal: the plan saves whole (E4-S16 D7). It is the one
	 * travel failure worth mentioning, because it is the only one they can do anything about.
	 */
	DELIVERY_ADDRESS_NOT_FOUND(400078, 409,
			"We couldn't find that address on the map.",
			"The plan is saved. Check the address if you want a travel estimate for it — a landmark and a pin code usually help."),

	/**
	 * The van is still on the road when the guests sit down.
	 *
	 * <p><strong>A refusal, unlike its neighbour above.</strong> An address a map service cannot
	 * place costs the plan a travel estimate and nothing else, so it warns. This is the temple's own
	 * arithmetic — its ready-by, its travel allowance, its serving time — and it does not add up.
	 * Rajeev settled it on 2026-09-05: <em>"People Sit to eat time MUST be = Ready by time + transit
	 * time at a minumum."</em>
	 *
	 * <p>That floor is deliberately the impossible line and not the sensible one. Loading, unloading
	 * and setting up all take time this application has no way to know, so meeting the floor is not
	 * the same as the plan being workable — the composer says so separately, and does not refuse it.
	 * What is refused is only what cannot happen at all.
	 */
	DELIVERY_CANNOT_ARRIVE_IN_TIME(400079, 409,
			"The food cannot get there before the guests sit down.",
			"Cook it earlier, serve it later, or check the travel allowance — and leave time to load it."),

	/**
	 * A meal that is cooking something has to say how many people it is cooking for. Everything the
	 * plan is worth — how much of each preparation to make, what it will cost, what a serving of it
	 * costs — is worked out from that number, and the application used to supply 100 of its own when
	 * nobody had said. A meal with nothing in it yet is fine: nobody has said what or for how many.
	 */
	MEAL_HEAD_COUNT_REQUIRED(400080, 409,
			"This meal has something being cooked, so it needs to know how many people are expected.",
			"Enter how many adults, children or seniors are coming. Every preparation is worked out from that number."),

	EXPORT_REQUIRED_BEFORE_DELETE(400081, 409,
			"Take a data export before deleting this temple.",
			"Download the temple's data export, then delete. Deleting erases everything permanently, and the export is the only copy."),

	PAYMENT_CREDENTIALS_REJECTED(400082, 409,
			"Your payment provider didn't accept those details.",
			"Check the key ID and secret against your provider's dashboard and try again. Nothing has been saved."),

	PAYMENT_PROVIDER_UNSUPPORTED(400083, 409,
			"We can't collect donations through that provider yet.",
			"Choose one of the providers offered, or ask us to add yours."),

	PAYMENT_NOT_CONFIGURED(400084, 409,
			"This temple hasn't set up a payment gateway yet.",
			"Add your provider's key ID and secret under Settings, then try again."),

	// The next step changed on 2026-09-08, with T-014, and the old one had never worked. It read
	// "Hire them again to bring them back" — but `hire` refuses anyone whose user id already has a
	// staff profile (StaffEmploymentService:122 → employmentFor, a lookup on `user_id` with **no
	// status filter**), so a former employee carrying an account was answered PERSON_ALREADY_EMPLOYED
	// and the advice ran into a wall. It was the docket item M9 in miniature: the product told people
	// to use a way back that did not exist. T-014 built the way back, so the sentence now names it.
	EMPLOYMENT_ALREADY_ENDED(400085, 409,
			"This person no longer works at your temple.",
			"A past employment record can be read but not changed. Take them back on from their record if they have returned."),

	COMMUNICATION_ALREADY_SENT(400086, 409,
			"This message has already gone out.",
			"A sent message can't be changed or sent again. Write a new one if you need to say more."),

	COMMUNICATION_HAS_NO_AUDIENCE(400087, 409,
			"Nobody would receive this message.",
			"Everyone has either not agreed to be contacted or has turned off this kind of message. Try a different kind, or check your devotee list."),

	STAFF_ACCESS_NEEDS_CONTACT(400088, 409,
			"Someone can only be given a sign-in if we have both their email address and their phone number.",
			"Add the missing one, or hire them without app access."),

	// --- Conflicts added for the 2026-08-20 build ----------------------
	//
	// Grouped in the order the build works through them: leave, then staff pay, then meal
	// recording, then bans, then the notice board. The grouping is for whoever reads the file; it
	// is not carried by the numbers, which run in declaration order like everything else.

	// Leave (B7)
	LEAVE_OVERLAPS_EXISTING(400089, 409,
			"This person already has leave recorded across some of those dates.",
			"Open their leave and change the existing record, or choose dates that don't overlap."),

	LEAVE_ALREADY_DECIDED(400090, 409,
			"That request has already been answered.",
			"An approved request can be revoked; a declined one can't be answered twice."),

	LEAVE_NOT_APPROVED(400091, 409,
			"Only approved leave can be revoked.",
			"A request still waiting can be declined instead."),

	CANNOT_SCHEDULE_OVER_LEAVE(400092, 409,
			"This person is on approved leave that day.",
			"Revoke the leave first if they are in after all."),

	SWAP_NEEDS_TWO_DAYS(400093, 409,
			"A swap needs two different days.",
			"Pick the day they'll work instead."),

	// Staff pay (B8)
	DEDUCTIONS_EXCEED_GROSS(400094, 409,
			"Those deductions come to more than the payment itself.",
			"Recover less this time; the rest of the advance stays outstanding."),

	DEDUCTION_EXCEEDS_ADVANCE(400095, 409,
			"That's more than is still outstanding on the advance.",
			"Recover what's left of it, or choose a different advance."),

	ADVANCE_ALREADY_RECOVERED(400096, 409,
			"That advance has already been recovered in full.",
			"There's nothing left on it to deduct."),

	// Reworded 2026-08-20. It used to refuse a payment with deductions and tell the reader to
	// "void the deductions first" — a door that does not exist, which made a mistyped docked
	// salary permanent. Voiding a payment now voids its deductions with it, so the only thing this
	// still refuses is striking an advance somebody has actually been docked for, and it says so.
	STAFF_PAYMENT_NOT_VOIDABLE(400097, 409,
			"Money has already been recovered against this advance.",
			"Void the payment that recovered it first, and this advance can then be struck."),

	// Meal recording and the job card (B4, B5)
	// The next step said "What was cooked can't be changed afterwards. Ask a Temple Admin if the
	// figures are wrong." until T-007 made the first sentence false, and the replacement dropped the
	// half that was still true. CORRECT_RECORDED_MEAL is TEMPLE_ADMIN's alone (RolePermissions:52),
	// while MANAGE_MEAL_PLANS — which is what gets somebody here, by recording an already-recorded
	// meal — is held by admin, manager and kitchen staff alike. So most people who see this were
	// being told to do something the API would refuse them. It names the door and who can open it.
	MEAL_ALREADY_RECORDED(400098, 409,
			"This meal has already been recorded.",
			"Ask a Temple Admin to record a correction if the figures are wrong."),

	MEAL_NOT_RECORDABLE(400099, 409,
			"This meal can't be recorded.",
			"A cancelled meal never went to the kitchen, so there is nothing to record against it."),

	// Bans and the check at hire (B9)
	BAN_ALREADY_EXISTS(400100, 409,
			"Your temple has already recorded this against that person.",
			"Open the existing record to update or retract it."),

	BAN_ALREADY_RETRACTED(400101, 409,
			"That record has already been retracted.",
			"A retracted record stays on file but no longer shows at a hire."),

	// Recipes
	RECIPE_IN_USE(400102, 409,
			"This recipe has been cooked, so it can't be deleted.",
			"Archive it instead — it will stop appearing when you plan a meal, and the record of what was cooked stays intact."),

	// The shared recipe library (E2-S12)
	RECIPE_ALREADY_ADDED(400103, 409,
			"You already have this recipe.",
			"Open it from your list to change your copy."),

	// 400104 was RECIPE_NEEDS_PROHIBITED_INGREDIENT — the import's half of the same block.
	// Retired 2026-09-08 by D-18 for the same reason as 400037, and D-18 names this refusal
	// specifically as a live guard being given up on purpose: an imported recipe naming garlic now
	// imports cleanly. The number is never reallocated.

	MASTER_RECIPE_NOT_FOUND(400105, 404,
			"That recipe is no longer in the shared library.",
			"Search again — it may have been renamed or taken down."),

	// Kitchens, and asking the store for ingredients (E10)
	KITCHEN_NAME_TAKEN(400106, 409,
			"Your temple already has a kitchen with that name.",
			"Pick a name that tells them apart, like the part of the temple it serves."),

	KITCHEN_IN_USE(400107, 409,
			"This kitchen has asked for ingredients before, so it can't be removed.",
			"Archive it instead — it stops appearing in the lists and its history stays readable."),

	KITCHEN_NOT_FOUND(400108, 404,
			"We couldn't find that kitchen.",
			"It may have been archived. Open the kitchens list and pick from there."),

	KITCHEN_ARCHIVED(400109, 409,
			"That kitchen has been archived.",
			"Restore it from the kitchens list first, or pick a different one."),

	KITCHEN_PLANS_ITS_OWN_MEALS(400110, 409,
			"This kitchen plans its meals here, so its ingredients are drawn when a meal is recorded.",
			"Pick a kitchen that only asks for ingredients, or turn the meal planner off for this one."),

	INGREDIENT_REQUEST_NOT_FOUND(400111, 404,
			"We couldn't find that request.",
			"Open the ingredient requests list and pick from there."),

	NOT_YOUR_INGREDIENT_REQUEST(400112, 403,
			"This request belongs to somebody else.",
			"You can read it, but only the person who wrote it can change it."),

	INGREDIENT_REQUEST_NOT_EDITABLE(400113, 409,
			"This request can no longer be changed.",
			"Raise a new request for anything else the kitchen needs."),

	INGREDIENT_REQUEST_ALREADY_DECIDED(400114, 409,
			"Somebody has already answered this request.",
			"Open it to see the answer and who gave it."),

	INGREDIENT_REQUEST_NOT_APPROVED(400115, 409,
			"This request hasn't been approved yet.",
			"It has to be approved before the store can issue against it."),

	INGREDIENT_REQUEST_ALREADY_ISSUED(400116, 409,
			"The store has already issued against this request.",
			"Raise a new request if the kitchen needs more."),

	INGREDIENT_REQUEST_EMPTY(400117, 409,
			"This request doesn't ask for anything yet.",
			"Add at least one ingredient before sending it for review."),

	INSUFFICIENT_STOCK_TO_ISSUE(400118, 409,
			"The store doesn't hold enough of everything on this request.",
			"Nothing was issued. Count the shelf and correct the stock, or issue a smaller amount of what is short."),

	INGREDIENT_REQUEST_NOT_SUBMITTED(400119, 409,
			"This request hasn't been sent for review yet.",
			"Open it and send it for review, then it can be approved or turned down."),

	KITCHEN_MAIN_MOVED(400120, 409,
			"Somebody else changed your temple's main kitchen a moment ago.",
			"Open the kitchens list to see which one holds it now, then set it again if you still want to."),

	INGREDIENT_REQUEST_NEEDS_DISHES(400121, 409,
			"Say what the kitchen is cooking before sending this for review.",
			"List each dish and how much of it, so whoever reviews this can judge the amounts."),

	// Cost per serving by meal kind (E3-S9). The period is asked for on the screen rather than
	// typed into a field, so getting it wrong takes two mis-clicks — but the report walks every
	// dish planned in the range, and an unbounded one would be a slow page rather than an answer.
	COST_PERIOD_NOT_VALID(400122, 400,
			"That period doesn't work.",
			"The last day has to fall on or after the first, and the period can cover at most a year."),

	// The notice board (E9-S1)
	NOTICE_ALREADY_WITHDRAWN(400123, 409,
			"This notice has already been withdrawn.",
			"Everyone who saw it has been shown the withdrawal."),

	// Bringing a scrapped machine back (D-15). The mirror of EMPLOYMENT_NOT_ENDED: the ordinary
	// path stays closed and there is one named way back, so asking for the way back on something
	// that never left has to say so rather than quietly succeeding.
	EQUIPMENT_NOT_SCRAPPED(400124, 409,
			"This item hasn't been scrapped.",
			"There is nothing to reinstate."),

	// A shift saying which meal it is for (D-14). The three columns are all-or-nothing, so a
	// half-filled link is refused by name rather than by a database constraint the reader can't read.
	SHIFT_MEAL_LINK_INCOMPLETE(400125, 400,
			"A shift linked to a meal needs the date and the meal kind together.",
			"Give both, or leave the shift unlinked so it counts by its hours."),

	// Removing a kind of meal (T-038, docket A3). A kind is stored as a NAME and not as a reference
	// — meal_plans.meal_kind, meal_services.meal_kind and shifts.meal_kind are all plain text,
	// because meal_kinds is unique on an expression index and PostgreSQL will not accept one as a
	// foreign-key target. That made the old delete look free. It is not: four read paths resolve
	// the stored text back through MealKindService.require() — the reuse-a-plan preview walking
	// historical plans, and the job-card language, document-list and print paths for a meal already
	// served — so deleting a kind that has ever been used makes history nobody touched throw
	// MEAL_KIND_UNKNOWN, days or months later, on a screen that has nothing to do with settings.
	// The refusal points at renaming, which cascades across those three columns and is the safe
	// answer to "we call it something else now".
	MEAL_KIND_IN_USE(400126, 409,
			"Meals have already been planned or recorded as this kind.",
			"Rename it instead. Everything recorded under it takes the new name."),

	// Supplies on the ingredient catalogue (T-023, D-1). LPG, leaf plates, hand soap and dishwashing
	// liquid are bought, received, stored and issued exactly as food is, so they are one flag on
	// `ingredients` rather than a second catalogue with its own stock ledger. The one place the two
	// genuinely differ is a recipe: a mop is not an ingredient of anything. The recipe picker hides
	// supplies, but a picker is not a guard — this is what the server says when a supply is posted
	// onto a recipe anyway.
	NOT_A_FOOD_INGREDIENT(400127, 409,
			"That's a supply, not something you can cook with.",
			"Choose a food ingredient, or add this one to the catalogue as food."),

	// A purchase-order line that names something the catalogue has never heard of (T-024, D-1).
	// Four plastic stools from a furniture shop want the opposite of a catalogue entry: nothing
	// invented in `ingredients`, and nothing landing in stock. So a line carries either an
	// ingredient or a description, and the column pair is exclusive — the database says so with a
	// CHECK, and this says the same thing in words the person filling the form can act on.
	PURCHASE_LINE_NEEDS_A_SUBJECT(400128, 400,
			"Each line needs either an ingredient or a description, not both and not neither.",
			"Pick an ingredient, or describe what you're buying."),

	// The other half of the same rule (T-024). A described line is orderable and payable and never
	// receivable: `goods_receipt_lines.ingredient_id` and `stock_movements.ingredient_id` are both
	// NOT NULL and stay that way, because the store tracks things it can count. Receiving an order
	// that contains one skips that line visibly; this is what a receipt that tries to take the
	// described line into stock is told, rather than a constraint violation nobody can read.
	CANNOT_RECEIVE_A_DESCRIBED_LINE(400129, 409,
			"A described line can't be received into stock.",
			"Record it as delivered on the order; it isn't something the store tracks."),

	// A vendor you walk into has no WhatsApp number (T-025, D-2). `vendors.phone` was NOT NULL only
	// because the phone IS the WhatsApp destination a purchase order is sent to, and that reason
	// does not apply to a shop somebody walks into and pays at the counter. So the column relaxes
	// (V101, the E.164 check kept for anything that IS present) and the send refuses instead, here,
	// BEFORE the order is moved DRAFT -> SENT. Note this is not `vendors.whatsapp_reachable`, which
	// is a stored preference and says nothing about whether a number exists.
	VENDOR_HAS_NO_WHATSAPP_NUMBER(400130, 409,
			"This vendor has no phone number to send to.",
			"Download the order and hand it over, or add a number to the vendor."),

	// Adding a line to the shopping list by hand (T-027). `shopping_list_lines` is unique on
	// (tenant_id, ingredient_id) — V25:44, renamed V81:48-49 — so a hand-add of something already
	// listed is a duplicate, not a second row. It refuses rather than upserting, because silently
	// overwriting the quantity the regenerator worked out is not what somebody typing a new line
	// meant to do, and the line they wanted is already on the screen in front of them.
	ALREADY_ON_THE_SHOPPING_LIST(400131, 409,
			"That's already on the shopping list.",
			"Change the quantity on the line that's there."),

	// Correcting money that was entered wrongly (wave 7). The four below share a shape and it is
	// worth naming once: each refuses a *second* correction of something already corrected, and each
	// names the correction that already exists rather than merely saying no. A temple admin who
	// reaches one of these has not made a mistake — they are looking at a stale screen — so the next
	// step points at the record that is already there.

	// Voiding an invoice already voided (T-010). Note this is a refusal and not the silent
	// idempotent return that StaffPayService.voidPayment gives: that endpoint carries no body, so a
	// second call is literally a double-click, while these carry a reason and a second reason is a
	// second act. Swallowing it would discard what the admin typed.
	INVOICE_ALREADY_VOIDED(400132, 409,
			"This invoice has already been voided.",
			"Look at the credit note recorded against it."),

	// Reversing a payment already reversed (T-010). `invoice_payments` is append-only (V40:33), so a
	// reversal is a compensating negative row rather than a mark on the original — which is exactly
	// why this guard is needed: nothing about the original row stops it being reversed twice.
	PAYMENT_ALREADY_VOIDED(400133, 409,
			"This payment has already been struck.",
			"Record a new payment if one was actually made."),

	// Voiding a gift already voided (T-012). Voiding also reverses the in-kind stock, so a second
	// void would reverse it twice and leave the store-room short of what was actually given.
	DONATION_ALREADY_VOIDED(400134, 409,
			"This donation has already been voided.",
			"Record it again if it was actually received."),

	// Reinstating somebody who never left (T-014). The mirror of EMPLOYMENT_ALREADY_ENDED, and the
	// same shape as EQUIPMENT_NOT_SCRAPPED (KMS-400124): an undo offered for a state the record is
	// not in.
	EMPLOYMENT_NOT_ENDED(400135, 409,
			"This person is still employed.",
			"There is nothing to reinstate."),

	// Reinstating somebody this temple raised a record against when they left (T-014, B9). Refused
	// rather than warned: a record under B9 carries a reason across every ISKCON temple on the
	// platform, and quietly letting one temple hire back over its own record would make the record
	// worth less everywhere else. The way back is to retract the record, which is a deliberate,
	// audited act with a screen of its own.
	EMPLOYMENT_RECORD_ON_FILE(400136, 409,
			"There is a record against this person from when they left.",
			"Retract that record first if they are to be taken back."),

	// Correcting a meal that has already been corrected (T-007). The mirror of
	// MOVEMENT_ALREADY_CORRECTED one grain up: a meal is a *set* of consumption movements, and a
	// second correction would compensate an already-compensated set and draw the store-room down
	// twice for food that was cooked once. Correct the correction if the figure is still wrong.
	MEAL_ALREADY_CORRECTED(400137, 409,
			"This meal has already been corrected.",
			"Look at the correction that was recorded against it."),

	// Retrying a message every copy of which arrived (T-015). Refused rather than treated as a
	// no-op, because a silent success is exactly the failure this feature exists to fix: the
	// per-recipient queueing statement used to swallow a retry and report that it had worked.
	NOTHING_FAILED_TO_RETRY(400138, 409,
			"Every copy of this message was delivered.",
			"There is nothing to send again."),

	// Marking a shift's attendance twice (T-016). The blanket path commits a screenful of ticks in
	// one press, so letting it run again would let a coordinator opening an already-marked roster
	// days later replace every considered answer at once. The refusal is load-bearing and stays.
	//
	// This next step has now been right three times for three different reasons, which is worth
	// recording. It said "Change it on the shift's roster." until T-016's builder pointed out the
	// roster could not — nothing in the product changed a mark once made — so it became "Look at the
	// roster to see who was marked", which was merely true. T-079 then built the correction, one
	// volunteer and one answer at a time, and the sentence can finally name a door that both exists
	// and opens: the roster carries a button per row for the answer it does not hold.
	ATTENDANCE_ALREADY_RECORDED(400139, 409,
			"Attendance for this shift has already been recorded.",
			"Change the mark on the shift's roster, beside the volunteer's name."),

	// Returning goods to a vendor after they were accepted (T-013). Rejection only ever worked at
	// the gate — the rejected quantity is a field of the receiving submission itself — so weevils
	// found the next morning, or 50 kg keyed instead of 5, had no path at all. A return reduces
	// stock through a movement like everything else, never by editing the receipt.
	RETURN_EXCEEDS_RECEIVED(400140, 400,
			"You can't return more than was received.",
			"Check the quantity against the goods receipt."),

	// 400141 was reserved for ALREADY_RETURNED and RETIRED UNUSED on 2026-09-09, before it ever
	// shipped. It assumed a receipt is returned once, wholly. T-013 built the opposite and the
	// docket's own examples require it: weevils are found in two sacks of a twenty-sack delivery,
	// and "50 kg keyed instead of 5" needs 45 back of 50 — neither is expressible in a whole-return
	// model, and "you cannot return more than was received" only means anything once a quantity is
	// chosen. So there is no returned flag, only a remaining quantity, and over-returning is
	// KMS-400140 whether the line was returned against before or not.
	//
	// The number is burnt deliberately rather than handed to the next task. Codes are never reused,
	// and a reader meeting 400141 in an old note should find this explanation rather than a
	// different meaning.

	// Retrying a message none of whose copies has failed *yet* (T-084). Split out of
	// NOTHING_FAILED_TO_RETRY, which claimed "every copy was delivered" for a message that may still
	// be in flight — a confidently wrong sentence about the one thing the sender wants to know.
	NOTHING_FAILED_YET(400142, 409,
			"No copy of this message has failed.",
			"Some are still on their way. Check back shortly."),

	// Retrying a draft (T-084). Also split out of NOTHING_FAILED_TO_RETRY, which told the sender a
	// message that had never been sent was fully delivered.
	COMMUNICATION_NOT_SENT(400143, 409,
			"This message hasn't been sent yet.",
			"Send it first, and you can then send it again to anyone it failed for."),

	// Marking attendance for a shift that has not run (T-085). The mirror of SHIFT_ALREADY_STARTED,
	// which guards releasing a signup in the other direction. Without it a coordinator opening
	// tomorrow's roster and pressing Save records the whole crew as having attended a shift that has
	// not happened — and that mark feeds reliability and hours-contributed for good.
	SHIFT_NOT_STARTED(400144, 409,
			"This shift hasn't run yet.",
			"Attendance can be marked once it has started."),

	// An invoice quoting a purchase order that belongs to a different vendor (T-082). The screen
	// makes this impossible by construction — the order is a dropdown of the chosen vendor's own
	// open orders — but the endpoint is reachable directly, and the two checks it used to make were
	// independent existence checks that never asked whether the pair agreed. The variance figure is
	// about money owed, so an invoice matched to the wrong order computes it from the wrong order.
	INVOICE_ORDER_NOT_FOR_VENDOR(400145, 409,
			"That purchase order belongs to a different vendor.",
			"Choose an order raised for the vendor on this invoice."),

	// A message that was sent and reached nobody (T-094). Split out of COMMUNICATION_NOT_SENT,
	// which told the sender of such a message to "send it first" — and requireDraft then refused
	// them with COMMUNICATION_ALREADY_SENT (KMS-400086). Two errors that contradict each other, with
	// the message reaching nobody by either route. Exactly the shape of KMS-400098, found in the
	// same wave: an error whose next step names a door the reader is not allowed through.
	// The next step said "Check who is set to receive this category" until T-094's builder pointed
	// out that it names the wrong cause: send() already refuses an empty audience with
	// COMMUNICATION_HAS_NO_AUDIENCE (KMS-400087) before it ever writes SENT, so a SENT message
	// always had an audience. What actually happened is that the relay refused every copy, and the
	// old sentence sent the reader to audit a list that was fine. Amended before it ever reached a
	// user — which is the whole reason this wave read its own error text against the code.
	COMMUNICATION_REACHED_NOBODY(400146, 409,
			"This message was sent, but no copy of it ever reached anybody.",
			"There is nothing here to send again. Write the message again and send it."),

	// --- Internal -----------------------------------------------------
	UNEXPECTED_FAILURE(500001, 500,
			"Something went wrong at our end.",
			"Try again in a moment. If it keeps happening, quote the code below."),

	// --- External services --------------------------------------------
	WHATSAPP_SEND_FAILED(500002, 502,
			"We couldn't send that message on WhatsApp.",
			"The number may be wrong or unreachable. You can download the document and share it manually."),

	TRANSLATION_FAILED(500003, 502,
			"We couldn't translate this right now.",
			"The English version is still available. Try translating again shortly."),

	DOCUMENT_GENERATION_FAILED(500004, 502,
			"We couldn't produce that document.",
			"Try again in a moment."),

	PAYMENT_GATEWAY_ERROR(500005, 502,
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

	/** The reference shown to the user and quoted to support, e.g. {@code KMS-500002}. */
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
