package org.iskcon.kms.occasion;

/**
 * How an occasion's dates are determined (E4-S2).
 */
public enum OccasionType {

	/** Astronomical: resolves to the calendar days the engine marks with a matching festival. */
	COMPUTED,

	/** Local and fixed: recurs on a set month and day each year (e.g. a temple anniversary). */
	MANUAL
}
