package org.iskcon.kms.inventory;

/**
 * Why a manual {@link MovementType#ADJUSTMENT} was made. Mandatory on adjustments (E3-S7) and on the
 * compensating movements that correct an earlier mistake; null for every other movement type.
 * Stored as text with a CHECK constraint mirroring this set.
 *
 * <p>The categories exist so the Phase 2 waste report can tell spoilage from a counting error
 * without parsing free text — {@link #OTHER} carries a required note for the cases that don't fit.
 */
public enum AdjustmentReason {

	/** Food went off before it could be used. */
	SPOILAGE,

	/** Physically damaged — spilled, broken, contaminated. */
	DAMAGE,

	/** A stock-take found the ledger and the shelf disagreed; this reconciles them. */
	COUNT_CORRECTION,

	/** Prepared or served food thrown away — trimmings, leftovers, over-production. */
	WASTE,

	/** None of the above; the note explains it. */
	OTHER
}
