package org.iskcon.kms.communication;

import java.util.Arrays;
import java.util.List;

/**
 * What kind of message this is, and therefore whether a devotee may decline it (E8-S1).
 *
 * <p>The split is the whole point. Consent alone was a switch with one setting too few: somebody who
 * does not want the newsletter still wants to be told their shift moved, and making them choose
 * between the two teaches them to withdraw consent entirely — which silences the reminders the
 * kitchen depends on.
 *
 * <p>So {@link #OPERATIONAL} is never opt-out-able and never composed by hand. Everything in it is
 * the consequence of something the person already did: a shift they took, a gift they gave, a
 * schedule they work to. Every other category is something the temple chose to say, and a devotee
 * may say no to any of them, or to all of them at once.
 *
 * <p>Kept in code rather than a database CHECK, like {@code AuditAction}: a new kind of message
 * should not be a migration. Names are stored as text, so treat an existing one as permanent.
 */
public enum CommunicationCategory {

	NEWSLETTER(
			"Newsletter",
			"The temple's regular letter to its community.",
			true),

	FESTIVAL_ANNOUNCEMENT(
			"Festivals and events",
			"Janmashtami, Gaura Purnima, and the programmes around them.",
			true),

	SEVA_OPPORTUNITY(
			"Seva opportunities",
			"When the kitchen needs hands and has not filled a shift. Not a reminder for seva you already took.",
			true),

	APPEAL(
			"Appeals for support",
			"Fundraising, and drives for the things the kitchen is short of.",
			true),

	TEMPLE_NOTICE(
			"Temple notices",
			"Closures, changed timings, and other practical news. Worth keeping on.",
			true),

	/**
	 * Everything the system sends of its own accord. Not offered on the compose screen and not
	 * offered on the preferences screen — it is shown there only so a devotee can see what will keep
	 * reaching them whatever else they turn off.
	 */
	OPERATIONAL(
			"Reminders and receipts",
			"Your shifts, changes to them, and confirmations of what you have given. Always sent.",
			false);

	private final String label;
	private final String description;
	private final boolean optional;

	CommunicationCategory(String label, String description, boolean optional) {
		this.label = label;
		this.description = description;
		this.optional = optional;
	}

	public String label() {
		return label;
	}

	public String description() {
		return description;
	}

	/** True when a devotee may decline it. False for exactly one category, and that is the design. */
	public boolean isOptional() {
		return optional;
	}

	/** The categories a temple admin may actually write a message in. */
	public static List<CommunicationCategory> composable() {
		return Arrays.stream(values()).filter(CommunicationCategory::isOptional).toList();
	}

	/** Parses leniently for a link somebody clicked; unknown text is nothing rather than a failure. */
	public static CommunicationCategory parseOrNull(String name) {
		if (name == null) {
			return null;
		}
		try {
			return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
