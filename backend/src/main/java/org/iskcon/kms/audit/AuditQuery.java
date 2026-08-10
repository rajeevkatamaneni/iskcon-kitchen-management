package org.iskcon.kms.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * The filters the viewer offers: a date range, an action type, an actor, plus the pagination
 * cursor and page size. Any field may be null, meaning "don't narrow by this".
 */
public record AuditQuery(
		Instant from,
		Instant to,
		String action,
		UUID actorUserId,
		AuditCursor cursor,
		int limit) {

	public static final int DEFAULT_LIMIT = 50;
	public static final int MAX_LIMIT = 200;

	/** Clamps the page size into a sane range, so no caller can ask for an unbounded scan. */
	public AuditQuery {
		if (limit <= 0) {
			limit = DEFAULT_LIMIT;
		} else if (limit > MAX_LIMIT) {
			limit = MAX_LIMIT;
		}
	}
}
