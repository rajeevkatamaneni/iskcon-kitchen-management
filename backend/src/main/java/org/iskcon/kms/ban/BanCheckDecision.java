package org.iskcon.kms.ban;

/**
 * What the admin did about what the check found (B9).
 *
 * <p>Recorded either way, and the reason to insist on that is {@link #PROCEEDED}: hiring somebody
 * anyway is a legitimate answer and often the right one. A temple that knows the person, or has rung
 * the temple that raised the record and heard the other half of it, should be able to go ahead — and
 * the product should say so plainly rather than making them feel they are overriding a machine.
 */
public enum BanCheckDecision {

	/** The check found nothing. Recorded so that "we looked" is on the record, not merely implied. */
	NO_FINDINGS,

	/** Findings were shown and the admin hired the person regardless. */
	PROCEEDED,

	/**
	 * Findings were shown and the admin stopped. This is the one outcome with no staff record to
	 * hang itself on — nobody was hired — so it lives only on the platform audit log. Without it,
	 * "we looked and walked away" would be indistinguishable from never having looked.
	 */
	ABANDONED
}
