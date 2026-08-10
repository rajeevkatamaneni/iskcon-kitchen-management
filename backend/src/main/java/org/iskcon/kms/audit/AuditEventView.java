package org.iskcon.kms.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One audit event as the viewer sees it. The JSONB before/after are parsed back into maps so the
 * API returns real JSON rather than escaped strings.
 */
public record AuditEventView(
		UUID id,
		String action,
		String entityType,
		UUID entityId,
		UUID actorUserId,
		String actorLabel,
		Map<String, Object> before,
		Map<String, Object> after,
		String reason,
		Instant createdAt) {
}
