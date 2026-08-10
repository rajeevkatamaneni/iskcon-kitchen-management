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

	// --- Not found ----------------------------------------------------
	TENANT_NOT_FOUND(4401, 404,
			"We couldn't find that temple.",
			"Check the address and try again."),

	RESOURCE_NOT_FOUND(4402, 404,
			"We couldn't find what you were looking for.",
			"It may have been removed."),

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

	INGREDIENT_IN_USE(4904, 409,
			"That ingredient is used by one or more recipes.",
			"Remove it from those recipes first, or keep it in the catalogue."),

	RECIPE_ALREADY_EXISTS(4905, 409,
			"A recipe with that name already exists.",
			"Choose a different name, or edit the existing recipe."),

	SATTVIC_INGREDIENT_BLOCKED(4906, 409,
			"This recipe contains an ingredient your temple treats as prohibited.",
			"Remove it, or ask a Temple Admin to save it with a reason."),

	CATEGORY_ALREADY_EXISTS(4907, 409,
			"A category with that name already exists.",
			"Choose a different name, or use the existing category."),

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
			"Try again in a moment.");

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
