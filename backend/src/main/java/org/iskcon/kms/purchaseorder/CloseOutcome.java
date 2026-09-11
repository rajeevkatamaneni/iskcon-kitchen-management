package org.iskcon.kms.purchaseorder;

/**
 * The three ways closing a part-delivered order can end for the vendor (T-142, D-26).
 *
 * <p><strong>This is the whole of the admin's influence over a supplier's score, and it is a name
 * rather than a number.</strong> Rajeev proposed letting the admin adjust the computed figure up
 * or down at closing time — <em>"there is SO MUCH human interaction that no machine or app can
 * capture"</em>, which is a real problem, correctly identified — and then ruled against his own
 * proposal when the costs were put to him: <em>"Let us not let the admin adjust the score. Just
 * show it to them."</em>
 *
 * <p>The four costs, kept because the idea will occur to somebody again:
 *
 * <ol>
 * <li>An adjustable score is not a measurement. It stops meaning <em>what this vendor did</em> and
 * starts meaning <em>what somebody felt about what they did</em>, so comparing two vendors becomes
 * comparing two admins' generosity.
 * <li>Nobody adjusts downward. Kindness accumulates in one direction until every vendor reads well
 * and the scorecard says nothing.
 * <li>It cannot be defended. <em>"An admin changed it"</em> is not an answer to a vendor who
 * disputes their score.
 * <li>It reopens what D-25 and T-129 closed the same day. Requiring an order to have been sent, and
 * excusing a vendor for an order we placed late, exist to make the number defensible; a free-hand
 * dial undoes all of it with one control.
 * </ol>
 *
 * <p>So the computed score is <em>shown</em> at closing — that transparency was the good half of
 * the idea and it stays — and what a person may say about it is one of these three words.
 */
public enum CloseOutcome {

	/**
	 * The vendor let us down: the one who went silent and never rang back.
	 *
	 * <p><strong>Scored exactly as computed, and that is not an oversight.</strong> T-124 scores
	 * on-time by quantity, so the 200 kg that never came is already in the percentage — 300 of 500
	 * inside the window is 60%, with nothing new to add. What this name does is make the record
	 * legible: a reader looking at a 60% can tell an order the temple holds against the supplier
	 * from one it accepts, which is a different fact from the arithmetic and is nowhere else.
	 *
	 * <p>It requires a sentence for the same reason T-124's no-show tick does. A permanent
	 * statement about somebody else's business with no explanation beside it is the record somebody
	 * will want to read back in a year and be unable to.
	 */
	VENDOR_LET_US_DOWN,

	/**
	 * They fell short but made it right: the one who apologised, blamed the weather, offered a
	 * discount next time and said buy it elsewhere.
	 *
	 * <p>The order leaves that vendor's score entirely — out of the on-time figure and out of the
	 * fill rate — with the reason recorded beside it. Both, because the black mark on a
	 * part-delivery is mostly the half-empty lorry: excusing the lateness and leaving the fill rate
	 * at 60% would waive almost nothing. That is a different case from D-25's late-sent order,
	 * which T-137 deliberately left in the fill rate, and the difference is nameable — ordering
	 * late excuses <em>our</em> timing, and this excuses <em>their</em> shortfall, which is the
	 * fill rate's own question.
	 *
	 * <p>The count of such orders sits beside both percentages on the scorecard. D-26 is explicit
	 * that the exclusions must be visible, and it is the same standard {@code ordersSentLate} and
	 * the abandoned count already meet: a number whose exclusions are invisible cannot be checked.
	 */
	SHORTFALL_EXCUSED,

	/**
	 * Neither. The figures stand as the receipts made them, and nothing is asserted about anybody.
	 *
	 * <p>The real case for it is the temple's own change of mind: we only needed the 300 after all,
	 * so the order is ended without a word for or against the supplier. No sentence is required,
	 * because nothing is being claimed.
	 */
	AS_COMPUTED
}
