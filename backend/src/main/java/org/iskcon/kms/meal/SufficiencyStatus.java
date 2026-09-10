package org.iskcon.kms.meal;

/** Whether a planned meal's ingredients are covered (E4-S5). */
public enum SufficiencyStatus {

	/** Every ingredient is covered by stock, after earlier meals took their share. */
	SUFFICIENT,

	/** At least one ingredient is short once earlier commitments are accounted for. */
	SHORT,

	/**
	 * Nothing is being claimed about stock for this meal, and there are now two ways to get here.
	 *
	 * <p>The original one: the recipe has no ingredient lines, so there is nothing to assess.
	 *
	 * <p>And since T-088: the meal is **not one of the claims that make up committed stock**, so
	 * nothing has reserved anything for it and no honest statement about sufficiency can be made.
	 * That covers a meal beyond the buying window — 14 days, extended to a festival within 30 —
	 * and a past-dated plan nobody ever recorded. **A meal three months out is not "ready" and not
	 * "short"; it will be cooked from stock nobody has bought yet, and saying either would be a
	 * guess dressed as a fact.** The screen renders this neutrally, which is the point.
	 */
	PLANNING
}
