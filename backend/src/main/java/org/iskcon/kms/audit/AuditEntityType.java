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
}
