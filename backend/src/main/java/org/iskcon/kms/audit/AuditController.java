package org.iskcon.kms.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading the audit log.
 *
 * <p>Two doors onto the same table, and which one you may open is the whole access model. A
 * Temple Admin reads their own temple's history through {@code /api/v1/audit-events}, scoped by
 * RLS to the tenant they are already in. A super-admin has no such door — they hold no
 * tenant-scoped audit permission at all — and instead drills into a single named temple through
 * {@code /api/v1/tenants/{tenantId}/audit-events}, an act that is itself recorded. There is
 * deliberately no endpoint that returns events across temples: the values in a before/after can
 * be a donation amount, and a cross-tenant feed would hand the operator exactly the data the
 * permission model withholds from them.
 */
@RestController
public class AuditController {

	private final AuditQueryService auditQueryService;

	public AuditController(AuditQueryService auditQueryService) {
		this.auditQueryService = auditQueryService;
	}

	/** The caller's own temple. RLS scopes it; a Temple Admin sees only their temple's events. */
	@GetMapping("/api/v1/audit-events")
	@PreAuthorize("hasAuthority('VIEW_AUDIT_LOG')")
	public AuditPage list(
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestParam(required = false) String action,
			@RequestParam(required = false) String actor,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false, defaultValue = "0") int limit) {

		return auditQueryService.forCurrentTenant(buildQuery(from, to, action, actor, cursor, limit));
	}

	/**
	 * A super-admin drilling into one temple's log. Gated by the platform-operations permission,
	 * not the temple audit permission — this is operating the platform, and it leaves a footprint
	 * in the temple's own log.
	 */
	@GetMapping("/api/v1/tenants/{tenantId}/audit-events")
	@PreAuthorize("hasAuthority('VIEW_PLATFORM_OPERATIONS')")
	public AuditPage drillIn(
			@PathVariable UUID tenantId,
			@AuthenticationPrincipal AuthenticatedUser operator,
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestParam(required = false) String action,
			@RequestParam(required = false) String actor,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false, defaultValue = "0") int limit) {

		return auditQueryService.asPlatformOperator(
				operator, tenantId, buildQuery(from, to, action, actor, cursor, limit));
	}

	private AuditQuery buildQuery(
			String from, String to, String action, String actor, String cursor, int limit) {

		return new AuditQuery(
				parseInstant(from, "from"),
				parseInstant(to, "to"),
				action,
				parseUuid(actor, "actor"),
				cursor == null ? null : AuditCursor.decode(cursor),
				limit);
	}

	private Instant parseInstant(String value, String field) {
		if (value == null) {
			return null;
		}
		try {
			return Instant.parse(value);
		} catch (RuntimeException e) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED, Map.of("field", field, "value", value), e);
		}
	}

	private UUID parseUuid(String value, String field) {
		if (value == null) {
			return null;
		}
		try {
			return UUID.fromString(value);
		} catch (RuntimeException e) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED, Map.of("field", field, "value", value), e);
		}
	}
}
