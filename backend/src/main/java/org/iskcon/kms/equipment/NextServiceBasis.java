package org.iskcon.kms.equipment;

/**
 * What the next service date was counted from (E3-S10 D4).
 *
 * <p>Carried beside the date itself so the screen can say which it did — "due 12 Mar 2027, from
 * purchase, never serviced" — and nobody reads a derived date as a service that happened.
 */
public enum NextServiceBasis {

	/** Counted from the newest recorded service. The normal case once a machine has a history. */
	SERVICED,

	/** Never serviced, so counted from the acquisition date instead. Said so, in as many words. */
	PURCHASED,

	/** Nothing to count from, or nothing to count. There is no next date. */
	NONE
}
