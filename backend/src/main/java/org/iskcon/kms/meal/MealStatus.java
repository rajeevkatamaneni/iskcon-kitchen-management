package org.iskcon.kms.meal;

/** Where a planned meal is in its life (E4-S4). */
public enum MealStatus {

	/** Planned but not yet cooked. Editable and cancellable. */
	PLANNED,

	/** Cooked — its ingredients have been drawn from stock. No longer editable; cancel is blocked. */
	COOKED,

	/** Called off before cooking. */
	CANCELLED
}
