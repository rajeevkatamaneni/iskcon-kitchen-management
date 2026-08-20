package org.iskcon.kms.notice;

/**
 * How loudly a platform notice asks to be read (E9-S1).
 *
 * <p>Three, and no more. The temptation with a severity scale is to add a fourth for the case that
 * does not quite fit, and the result is a scale nobody can rank in their head. Three is the number a
 * person can hold: something to know, something to act on, something to stop and deal with.
 *
 * <p>Only {@link #URGENT} is loud on screen. That restraint is the whole design — a board where
 * every notice shouts is a board people learn to scroll past, and the one time it matters they will
 * scroll past that too (build brief 2026-08-20, §11).
 */
public enum NoticeSeverity {

	/** Worth knowing. A change of platform hours, a festival advisory. Quiet on every screen. */
	INFORMATION,

	/** Worth acting on before long. A supplier who has stopped delivering, planned downtime. */
	IMPORTANT,

	/**
	 * Stop and deal with it. A food-safety recall, a contaminated batch. The only severity the
	 * design system's danger colour is spent on, which is why it still means something when used.
	 */
	URGENT,
}
