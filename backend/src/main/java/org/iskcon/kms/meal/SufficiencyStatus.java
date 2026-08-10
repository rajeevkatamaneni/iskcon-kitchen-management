package org.iskcon.kms.meal;

/** Whether a planned meal's ingredients are covered (E4-S5). */
public enum SufficiencyStatus {

	/** Every ingredient is covered by stock, after earlier meals took their share. */
	SUFFICIENT,

	/** At least one ingredient is short once earlier commitments are accounted for. */
	SHORT,

	/** Nothing to assess — the recipe has no ingredient lines. */
	PLANNING
}
