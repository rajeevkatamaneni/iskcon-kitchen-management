package org.iskcon.kms.receiving;

/**
 * Why part of a delivery was refused (E5-S6). Stored as text on
 * {@code goods_receipt_lines.reject_reason}, with a CHECK mirroring this set. Rejected goods never
 * enter stock; the reason is kept so a vendor's reliability can be read back (Phase 2 scorecard).
 */
public enum RejectReason {

	/** Physically damaged in transit or handling. */
	DAMAGED,

	/** Spoiled, expired, or otherwise unfit. */
	SPOILED,

	/** Not what was ordered. */
	WRONG_ITEM,

	/** Anything else — the receiver explains in the note. */
	OTHER,
}
