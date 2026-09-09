package org.iskcon.kms.receiving;

/**
 * Why goods already taken into stock were sent back to the vendor (T-013). Stored as text on
 * {@code goods_returns.reason}, with a CHECK mirroring this set.
 *
 * <p>Four of the five are {@link RejectReason}'s, said one day later, and they are repeated rather
 * than shared on purpose: the two enums answer different questions and are free to diverge.
 * A rejection is what the storekeeper refused off the lorry, and it never entered stock at all; this
 * is what left the store again after it had. Making {@code RejectReason} serve both would tie a
 * change in one screen's vocabulary to the other's for no reason beyond their happening to overlap
 * today.
 */
public enum ReturnReason {

	/** Physically damaged — found once the sacks were opened, not at the gate. */
	DAMAGED,

	/** Spoiled, infested, expired, or otherwise unfit. The weevils case. */
	SPOILED,

	/** Not what was ordered, discovered after it was booked in. */
	WRONG_ITEM,

	/**
	 * It was never delivered: the quantity was keyed wrongly and the stock is a phantom.
	 *
	 * <p>The one reason with no counterpart in {@link RejectReason}, and the reason this enum is
	 * its own type. Fifty kilos entered when five arrived is not damage and not the wrong item —
	 * nothing is physically going back, because nothing physically came. It still has to leave the
	 * ledger through a movement like everything else, and a scorecard reading these rows later
	 * needs to be able to tell "the vendor sent us bad rice" from "we mistyped".
	 */
	NOT_DELIVERED,

	/** Anything else — the person returning it explains in the note. */
	OTHER,
}
