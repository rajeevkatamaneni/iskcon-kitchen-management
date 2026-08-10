package org.iskcon.kms.audit;

/**
 * The vocabulary of auditable actions.
 *
 * <p>Kept in code rather than a database CHECK constraint: every epic adds actions, and a
 * constraint would turn each addition into a migration. The names are stored as text in
 * {@code audit_events.action}, so — like error codes — treat an existing name as permanent:
 * reword its meaning if you must, but a name already written to a log must keep meaning the same
 * thing, because someone may be reading a year-old entry.
 *
 * <p>Epic 1 covers the actions below. Later epics extend the set (overrides, stock adjustments,
 * payments).
 */
public enum AuditAction {

	/** A temple was brought onto the platform (E1-S6). Its {@code before} is null — a creation. */
	TENANT_PROVISIONED,

	/** A user's role was changed (E1-S7). The exemplar of before/after capture. */
	ROLE_CHANGED,

	/**
	 * A role change was refused by one of the guards. Recorded on purpose: a refused escalation
	 * is exactly what someone reviewing the log is looking for.
	 */
	ROLE_CHANGE_REJECTED,

	/**
	 * A platform super-admin drilled into this temple's audit log. Recorded so operator access to
	 * a temple's history is never silent.
	 */
	AUDIT_LOG_VIEWED,

	/**
	 * A person completed their first sign-in and bound their real Firebase identity to a
	 * previously pending account (E1-S6). Recorded because binding an identity to a pre-created,
	 * possibly privileged account is exactly the kind of event a temple should be able to see.
	 */
	ACCOUNT_CLAIMED,

	/** A Temple Admin added a person to the temple (E1-S12). */
	USER_ADDED,

	/** A user account was disabled — access blocked on their next request (E1-S12). */
	USER_DISABLED,

	/** A disabled account was restored (E1-S12). */
	USER_ENABLED,

	/** An ingredient was added to the catalogue (E2-S1). */
	INGREDIENT_ADDED,

	/** An ingredient's descriptive fields were edited (E2-S1). */
	INGREDIENT_UPDATED,

	/** An ingredient was removed from the catalogue (E2-S1). */
	INGREDIENT_DELETED,

	/**
	 * An ingredient's sattvic-prohibited flag was set or cleared (E2-S1). A religious-compliance
	 * decision, so it is recorded with who made it and the before/after.
	 */
	INGREDIENT_SATTVIC_FLAG_CHANGED,

	/** A recipe category was added (E2-S2). */
	RECIPE_CATEGORY_ADDED,

	/** A recipe was created (E2-S2). */
	RECIPE_CREATED,

	/** A recipe's fields or ingredient lines were edited (E2-S2). */
	RECIPE_UPDATED,

	/** A recipe was archived — soft-deleted, still renderable in history (E2-S2). */
	RECIPE_ARCHIVED,

	/**
	 * A Temple Admin saved a recipe containing a sattvic-prohibited ingredient, overriding the
	 * block with a reason (E2-S4). Exactly the kind of religious-compliance decision the log exists
	 * to make explainable.
	 */
	RECIPE_SATTVIC_OVERRIDDEN,

	/**
	 * A stock movement was corrected by a compensating movement (E3-S2). The ledger itself is
	 * append-only, so this records the deliberate act of reversing an earlier entry, with who did it
	 * and why.
	 */
	STOCK_MOVEMENT_CORRECTED,

	/** A consumable was added to the tracked inventory (E3-S1). */
	INVENTORY_ITEM_ADDED,

	/** A tracked consumable's metadata (location, reorder threshold, notes) was edited (E3-S1). */
	INVENTORY_ITEM_UPDATED,

	/** A consumable was removed from tracking; its movement history remains (E3-S1). */
	INVENTORY_ITEM_REMOVED,

	/**
	 * A large manual stock adjustment was made — over the fraction of on-hand that a Temple Admin
	 * must approve (E3-S7). Routine small adjustments live in the ledger alone; a big write-off is
	 * recorded here too, with the reason and the before/after, because it is exactly what a review
	 * would look for.
	 */
	STOCK_ADJUSTED,

	/** A piece of equipment was registered (E3-S4). */
	EQUIPMENT_ADDED,

	/** An equipment item's descriptive fields were edited (E3-S4). */
	EQUIPMENT_UPDATED,

	/**
	 * An equipment item's condition changed — sent for repair, returned, scrapped (E3-S4). Recorded
	 * temple-wide here in addition to the item's own history, because scrapping an asset is material.
	 */
	EQUIPMENT_CONDITION_CHANGED,
}
