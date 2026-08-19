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

	/**
	 * A temple and all its data were permanently deleted (E1-S15). Recorded on the <em>platform</em>
	 * audit log, not the temple's own — the temple's log is erased with it, so the only durable proof
	 * of the deletion lives at the platform level. Its {@code after} is null — a removal.
	 */
	TENANT_DELETED,

	/**
	 * A complete copy of one temple's data was taken as a workbook (E1-S15). Recorded on the
	 * <em>platform</em> log for the same reason as {@link #TENANT_DELETED} — the export exists to
	 * precede an erasure that destroys the temple's own log — and it is also the fact the deletion
	 * guard reads: no export in the last 24 hours, no deletion.
	 */
	TENANT_EXPORTED,

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

	/**
	 * An ingredient's Ekadashi-prohibited flag was set or cleared (E4-S6) — a religious-compliance
	 * decision, recorded with who made it, like the sattvic flag.
	 */
	INGREDIENT_EKADASHI_FLAG_CHANGED,

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

	/** An in-kind donation was received and recorded (E3-S5). */
	DONATION_RECORDED,

	/** A festival occasion was added to the catalog (E4-S2). */
	OCCASION_ADDED,

	/** A festival occasion was edited (E4-S2). */
	OCCASION_UPDATED,

	/** A festival occasion was removed (E4-S2). */
	OCCASION_REMOVED,

	/**
	 * A Temple Admin overrode a computed calendar date (E4-S3) — an astronomical edge case or a GBC
	 * ruling. Recorded with the before/after and reason, because it changes what the whole temple
	 * fasts and plans by.
	 */
	CALENDAR_OVERRIDDEN,

	/** A calendar override was removed, reverting the date to computed truth (E4-S3). */
	CALENDAR_OVERRIDE_REVERTED,

	/** A meal was planned (E4-S4). */
	MEAL_PLANNED,

	/** A planned meal was edited (E4-S4). */
	MEAL_PLAN_UPDATED,

	/** A planned meal was cancelled before cooking (E4-S4). */
	MEAL_PLAN_CANCELLED,

	/** A meal was marked cooked, drawing its ingredients from stock (E4-S4). */
	MEAL_COOKED,

	/** A vendor was added (E5-S1). */
	VENDOR_ADDED,

	/** A vendor's details were edited (E5-S1). */
	VENDOR_UPDATED,

	/** A vendor was deactivated — hidden from new orders, history preserved (E5-S1). */
	VENDOR_DEACTIVATED,

	/** A deactivated vendor was restored (E5-S1). */
	VENDOR_REACTIVATED,

	/** A purchase order was sent to the vendor (E5-S3). */
	PO_SENT,

	/** A purchase order was cancelled, with a reason (E5-S3). */
	PO_CANCELLED,

	/** A delivery was received against a PO, leaving it partially received (E5-S6). */
	PO_PARTIALLY_RECEIVED,

	/** A PO was fully received (E5-S6). */
	PO_RECEIVED,

	/** A vendor invoice was recorded against a PO or as a direct purchase (E5-S8). */
	INVOICE_RECORDED,

	/** A purchase order was sent to its vendor on WhatsApp, or re-sent (E5-S7). */
	PO_WHATSAPP_SENT,

	/** A poster sent a one-off broadcast to a shift's volunteers (E6-S7). */
	SHIFT_BROADCAST_SENT,

	/**
	 * Someone was hired (E6-S8). Recorded because it is the only act that grants a temple role —
	 * a hire may be given Temple Admin, and that should never be something only a role column knows.
	 */
	STAFF_HIRED,

	/**
	 * An employment record was edited (E6-S8) — a promotion, a corrected joining date, access
	 * granted or withdrawn. The before/after names the job title and the access, never the PAN.
	 */
	STAFF_UPDATED,

	/** Someone stopped working here (E6-S8), with how it ended and whether their sign-in was revoked. */
	STAFF_EMPLOYMENT_ENDED,

	/** An admin decrypted and viewed an employee's PAN (E6-S8). Access to PII is always recorded. */
	STAFF_PAN_VIEWED,

	/**
	 * A temple wrote to its whole community (E8-S3). Recorded with the subject and the number of
	 * people it reached — a message to four hundred devotees is the largest single act this product
	 * offers, and the only durable record of what was said to whom.
	 */
	COMMUNICATION_SENT,

	/** A Temple Admin decrypted and viewed a donor's PAN (E7-S4). Access to PII is always recorded. */
	DONOR_PAN_VIEWED,

	/** A monetary donation was confirmed by the payment provider (E7-S2/S3/S6). */
	DONATION_COMPLETED,

	/** A payment was recorded against a vendor invoice (E7-S8). */
	INVOICE_PAYMENT_RECORDED,

	/**
	 * A temple's own settings were changed — including its payment gateway credentials, and every
	 * reveal of the webhook secret they are configured with (E7). The secrets themselves are never
	 * in the record; who changed them, and when, always is.
	 */
	SETTINGS_UPDATED,
}
