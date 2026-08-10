package org.iskcon.kms.audit;

/**
 * What kind of thing an audit event is about. Stored as text in {@code audit_events.entity_type}
 * alongside the entity's id, so the viewer can group and link. Extended by later epics.
 */
public enum AuditEntityType {

	/** A temple. */
	TENANT,

	/** A user account. */
	USER,

	/** A temple's audit log itself — the entity of an {@link AuditAction#AUDIT_LOG_VIEWED} event. */
	AUDIT_LOG,

	/** An ingredient in the catalogue (E2-S1). */
	INGREDIENT,

	/** A recipe (E2-S2). */
	RECIPE,

	/** A recipe category (E2-S2). */
	RECIPE_CATEGORY,

	/** A stock movement in the inventory ledger (E3-S2). */
	STOCK_MOVEMENT,

	/** A tracked consumable inventory item (E3-S1). */
	INVENTORY_ITEM,

	/** A piece of equipment (E3-S4). */
	EQUIPMENT,

	/** A donation to the temple (E3-S5). */
	DONATION,

	/** A festival occasion (E4-S2). */
	OCCASION,

	/** A single day of the Vaishnava calendar — the entity of an override (E4-S3). */
	CALENDAR_DAY,
}
