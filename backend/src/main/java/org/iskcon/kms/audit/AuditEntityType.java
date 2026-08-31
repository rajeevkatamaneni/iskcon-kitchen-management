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

	/** A planned meal (E4-S4). */
	MEAL_PLAN,

	/** A vendor (E5-S1). */
	VENDOR,

	/** A purchase order (E5-S3). */
	PURCHASE_ORDER,

	/** A vendor invoice (E5-S8). */
	VENDOR_INVOICE,

	/** A volunteer shift (E6). */
	SHIFT,

	/** One person's employment at this temple (E6-S8). */
	STAFF_MEMBER,

	/** One record of time off, sick or unpaid leave (B7). */
	STAFF_LEAVE,

	/** A message a temple wrote to its community (E8-S2). */
	COMMUNICATION,

	/**
	 * A recipe in the shared library (E2-S9). Belongs to no temple — like a platform notice, and for
	 * the same reason: its value is that a recipe written for Vijayawada is read in Bangalore.
	 */
	MASTER_RECIPE,

	/**
	 * A notice on the platform-wide board (E9-S1). The one entity here that belongs to no temple —
	 * which is why the platform audit log admits a write about it from somebody who is not an
	 * operator (V66).
	 */
	PLATFORM_NOTICE,

	/**
	 * A ban raised at a dismissal (B9). Like a platform notice, it belongs to no single temple — the
	 * temple that raised it owns it, and every other temple may be shown it — which is why the
	 * platform audit log admits a write about it from an administrator who is not an operator (V65).
	 */
	EMPLOYMENT_BAN,

	/**
	 * One query of the ban list, made during one hire (B9). It has no row of its own anywhere: the
	 * platform audit entry <em>is</em> the record of it, and the id is what the admin's eventual
	 * answer is filed against.
	 */
	EMPLOYMENT_BAN_CHECK,

	/** One of the kitchens a temple runs (E10-S2). */
	KITCHEN,

	/** One kitchen's request to the store for ingredients (E10-S5). */
	INGREDIENT_REQUEST,
}
