package org.iskcon.kms.profile;

/**
 * The communication-consent a user gives under the DPDP Act: agreement to be contacted for a
 * stated purpose, on the contact details already on their account.
 *
 * <p>The text is versioned. When it is revised the version changes, and anyone whose recorded
 * version no longer matches is asked again — consent is to a purpose, and a changed purpose is a
 * new ask. Bump {@link #CURRENT_VERSION} whenever {@link #TEXT} changes in a way that alters what
 * a person is agreeing to.
 */
public final class CommunicationConsent {

	/** Date-stamped so a glance tells you which wording is current. */
	public static final String CURRENT_VERSION = "2026-08-10";

	public static final String TEXT =
			"I agree that my temple may send me reminders and service messages — such as "
					+ "volunteer shift reminders and order updates — by WhatsApp, SMS, or email, "
					+ "using the contact details on my account. I can change my preferred channel or "
					+ "withdraw this consent at any time from my profile.";

	private CommunicationConsent() {
	}
}
