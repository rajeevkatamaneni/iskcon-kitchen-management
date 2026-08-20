package org.iskcon.kms.notice;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.auth.Permission;
import org.iskcon.kms.auth.RolePermissions;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The platform notice board (E9-S1) — the one thing in this product that deliberately crosses
 * tenant isolation.
 *
 * <p>A notice raised by one temple is read by every temple, because that is the entire point: a
 * supplier recall found in Bengaluru is a recall in Mayapur too. There is no fan-out and no copy per
 * temple; there is one row, and V66's row-level security makes it readable by any verified identity
 * rather than by a tenant. The reasoning for that, and for why the <em>dismissal</em> table is the
 * one that carries tenant-shaped policy instead, is written out at the top of that migration.
 *
 * <p>Three rules from build brief §11 shape almost everything below:
 *
 * <ul>
 *   <li><strong>Dismissal is per person.</strong> Not per temple. A temple with three admins where
 *       the first clears a food-safety recall before the other two have read it is a temple where
 *       two people never saw it.
 *   <li><strong>Off Today after 30 days or when dismissed, permanent on the board.</strong> A notice
 *       that never leaves Today becomes wallpaper; one that vanishes cannot be found again when it
 *       matters.
 *   <li><strong>No pre-moderation, but withdrawal.</strong> A recall at nine on a Sunday evening
 *       cannot wait for a reviewer. What stands in for one: the raising temple is named in the open,
 *       the raiser is on the platform audit log, and it can be taken down — by that temple, or by an
 *       operator.
 * </ul>
 */
@Service
public class NoticeService {

	private static final Logger log = LoggerFactory.getLogger(NoticeService.class);

	/**
	 * How long a notice keeps its place at the top of Today. Long enough that a temple which opens
	 * the app weekly still meets it; short enough that the band empties by itself, which is what
	 * stops Today's most valuable strip of screen becoming permanent furniture.
	 */
	private static final int DAYS_ON_TODAY = 30;

	/**
	 * What a notice with no temple behind it is signed. Lower case because it is read inside a
	 * sentence — "Raised by the platform" — and a proper noun there would suggest an organisation the
	 * temple could ring, which is precisely what a platform notice is not.
	 */
	private static final String PLATFORM_LABEL = "the platform";

	private static final String SELECT_COLUMNS = """
			SELECT n.id, n.raised_by_tenant_id, n.raised_by_label, n.severity, n.subject, n.body,
			       n.created_at, n.withdrawn_at, n.withdrawn_by_label, n.withdrawn_reason
			""";

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public NoticeService(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	/**
	 * Every notice ever raised, newest first, withdrawn ones included — the permanent board behind
	 * the Today band.
	 *
	 * <p>Nothing is filtered out here, deliberately. A temple that dismissed a recall in March and
	 * needs it again in June has exactly one place to look, and a board that hid what had been
	 * dismissed would not be that place.
	 */
	public List<NoticeView> board(AuthenticatedUser actor) {
		return jdbc.query(SELECT_COLUMNS + """
				FROM platform_notices n
				ORDER BY n.created_at DESC, n.id DESC
				""", mapper(actor));
	}

	/**
	 * The notices at the top of one person's Today screen (E9-S1, §11) — <strong>the method the
	 * Today screen calls</strong>.
	 *
	 * <p>What it returns is everything inside the window that this person has not already cleared,
	 * plus one case that is easy to miss: a notice they <em>did</em> clear which has since been
	 * withdrawn. A withdrawal travels the same rails as the notice, so somebody who acted on a recall
	 * and dismissed it must be shown the retraction — otherwise the people most likely to have acted
	 * are the only ones never told it was called off. That is what the comparison against
	 * {@code dismissed_at} is doing, and why a dismissal row records the <em>last</em> dismissal
	 * rather than the first.
	 *
	 * <p>The window is measured from the withdrawal where there is one: a retraction is news on the
	 * day it happens, whatever the age of the thing it retracts.
	 *
	 * <p>A platform operator gets an empty list. They have no temple, so they have no Today screen
	 * and nowhere to dismiss a notice from; the board at /notices is their view of this.
	 */
	public List<NoticeView> undismissedFor(AuthenticatedUser actor) {
		if (actor.getTenantId() == null) {
			return List.of();
		}
		return jdbc.query(SELECT_COLUMNS + """
				FROM platform_notices n
				LEFT JOIN platform_notice_dismissals d
				       ON d.notice_id = n.id AND d.user_id = ?
				WHERE COALESCE(n.withdrawn_at, n.created_at) > now() - make_interval(days => ?)
				  AND (d.notice_id IS NULL OR (n.withdrawn_at IS NOT NULL AND n.withdrawn_at > d.dismissed_at))
				ORDER BY CASE
				             WHEN n.withdrawn_at IS NOT NULL THEN 3
				             WHEN n.severity = 'URGENT' THEN 0
				             WHEN n.severity = 'IMPORTANT' THEN 1
				             ELSE 2
				         END,
				         n.created_at DESC, n.id DESC
				""", mapper(actor), actor.getUserId(), DAYS_ON_TODAY);
	}

	/**
	 * Posts a notice to every temple on the platform.
	 *
	 * <p>No review stands between this call and two hundred temples, by design. Three things stand
	 * in its place, and all three happen here: the raising temple's name goes onto the row in the
	 * open, the act goes onto the platform audit log, and the notice remains withdrawable.
	 *
	 * <p>The raiser is taken from the verified principal and never from the request, so the row's
	 * attribution and the audit entry describe the same person by construction. V66's insert policy
	 * enforces the same thing underneath: a signed-in caller may only post as themselves.
	 */
	@Transactional
	public UUID raise(AuthenticatedUser actor, RaiseNoticeRequest request) {
		String label = actor.getTenantId() == null ? PLATFORM_LABEL : templeName(actor.getTenantId());

		UUID id = jdbc.queryForObject("""
				INSERT INTO platform_notices (
					raised_by_tenant_id, raised_by_user_id, raised_by_label, severity, subject, body)
				VALUES (?, ?, ?, ?, ?, ?)
				RETURNING id
				""",
				UUID.class,
				actor.getTenantId(), actor.getUserId(), label,
				request.severity().name(), request.subject().trim(), request.body().trim());

		Map<String, Object> after = new HashMap<>();
		after.put("severity", request.severity().name());
		after.put("subject", request.subject().trim());
		after.put("raisedBy", label);

		// The platform log is where a cross-tenant act belongs, and V66 widens its insert policy by
		// exactly this much so that a temple admin — who still cannot read that log — can append to
		// it. The temple's own log gets it too when there is a temple: raising a notice is an act of
		// theirs, and their admin should not have to ask an operator what their own people posted.
		auditService.recordPlatform(actor, AuditAction.NOTICE_RAISED, AuditEntityType.PLATFORM_NOTICE,
				id, null, after, null);
		if (actor.getTenantId() != null) {
			auditService.record(actor, AuditAction.NOTICE_RAISED, AuditEntityType.PLATFORM_NOTICE,
					id, null, after, null);
		}

		log.info("Platform notice {} raised at {} severity by {}", id, request.severity(), label);
		return id;
	}

	/**
	 * Posts a notice attributed to the platform rather than to a person — the machinery for a notice
	 * nobody had to remember to write.
	 *
	 * <p>Nothing calls this yet. It exists because scheduled maintenance and degraded performance are
	 * the two notices most certain to be needed and least likely to be posted by hand at the moment
	 * they matter; the callers, when they are built, are a maintenance-window job in the worker and
	 * the health monitor that already knows when the platform is struggling. Both run on a background
	 * thread with no signed-in person, which is exactly the shape V66's insert policy admits — no
	 * auth_uid on the connection, and both raiser columns null.
	 *
	 * <p>Deliberately <em>not</em> audited to the platform log: an audit row needs an actor, and the
	 * honest answer here is that there was no person. The attribution on the notice itself, and this
	 * log line, are the record. A synthetic "system user" would have been the alternative, and a
	 * fictitious actor on an audit log is worse than an absent one.
	 */
	@Transactional
	public UUID raiseFromPlatform(NoticeSeverity severity, String subject, String body) {
		// The id is minted here rather than returned by the insert, and that is not a style choice:
		// PostgreSQL applies a table's SELECT policy to the RETURNING clause as well as its WITH
		// CHECK, so `INSERT ... RETURNING id` asks this connection to *read* the row it just wrote.
		// A connection with no verified identity — which is exactly what a background job is, and
		// exactly what V66's policy admits for a platform-attributed notice — cannot read it, and
		// the insert fails with a row-level-security error that names the check rather than the
		// read. Writing blind is the honest shape for a writer that is deliberately nobody.
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO platform_notices (
					id, raised_by_tenant_id, raised_by_user_id, raised_by_label, severity, subject, body)
				VALUES (?, NULL, NULL, ?, ?, ?, ?)
				""", id, PLATFORM_LABEL, severity.name(), subject.trim(), body.trim());

		log.info("Platform notice {} raised at {} severity by automation: {}", id, severity, subject);
		return id;
	}

	/**
	 * Takes a notice down, with a reason.
	 *
	 * <p>Two callers may: the temple that raised it, and a platform operator. The operator's
	 * takedown is what makes going without pre-moderation defensible — a board anybody may post to
	 * needs somebody who can clear it — and it is the only place in the product where one temple's
	 * record is altered by someone outside it, which is why the reason is mandatory and the act is on
	 * the platform log.
	 *
	 * <p>The withdrawal is recorded on the notice itself rather than as a second notice. A retraction
	 * that arrived as a separate message would leave the original standing beside it, and the failure
	 * mode of a recall board is somebody reading the wrong one of two.
	 */
	@Transactional
	public void withdraw(AuthenticatedUser actor, UUID noticeId, String reason) {
		Standing standing;
		try {
			standing = jdbc.queryForObject("""
					SELECT raised_by_tenant_id, raised_by_label, severity, subject, withdrawn_at
					FROM platform_notices WHERE id = ?
					""", (rs, row) -> new Standing(
							(UUID) rs.getObject("raised_by_tenant_id"),
							rs.getString("raised_by_label"),
							rs.getString("severity"),
							rs.getString("subject"),
							rs.getObject("withdrawn_at", OffsetDateTime.class)),
					noticeId);
		} catch (EmptyResultDataAccessException e) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("noticeId", noticeId));
		}

		if (standing.withdrawnAt() != null) {
			throw new ApplicationException(ErrorCode.NOTICE_ALREADY_WITHDRAWN, Map.of("noticeId", noticeId));
		}
		if (!mayWithdraw(actor, standing.raisedByTenantId())) {
			throw new ApplicationException(ErrorCode.NOTICE_NOT_YOURS_TO_WITHDRAW,
					Map.of("noticeId", noticeId, "raisedBy", standing.raisedByLabel()));
		}

		String label = actor.getTenantId() == null ? PLATFORM_LABEL : templeName(actor.getTenantId());

		jdbc.update("""
				UPDATE platform_notices
				SET withdrawn_at = now(), withdrawn_by_user_id = ?, withdrawn_by_label = ?,
				    withdrawn_reason = ?
				WHERE id = ? AND withdrawn_at IS NULL
				""", actor.getUserId(), label, reason.trim(), noticeId);

		Map<String, Object> before = new HashMap<>();
		before.put("severity", standing.severity());
		before.put("subject", standing.subject());
		before.put("raisedBy", standing.raisedByLabel());
		Map<String, Object> after = new HashMap<>();
		after.put("withdrawnBy", label);

		auditService.recordPlatform(actor, AuditAction.NOTICE_WITHDRAWN, AuditEntityType.PLATFORM_NOTICE,
				noticeId, before, after, reason.trim());
		if (actor.getTenantId() != null) {
			auditService.record(actor, AuditAction.NOTICE_WITHDRAWN, AuditEntityType.PLATFORM_NOTICE,
					noticeId, before, after, reason.trim());
		}

		log.info("Platform notice {} withdrawn by {}", noticeId, label);
	}

	/**
	 * Clears a notice from this one person's Today screen, and nobody else's.
	 *
	 * <p>Idempotent, and the conflict does real work rather than being defensive tidiness: dismissing
	 * a notice one has already dismissed moves {@code dismissed_at} forward, which is how a person
	 * who clears a retraction stops being shown it again. V66's policy is what confines the write to
	 * the caller's own row — the application does not filter by person, the database does.
	 *
	 * <p>An operator has no Today screen and so nothing to dismiss from; asking is a programming
	 * error rather than a user one, and it is refused as such.
	 */
	@Transactional
	public void dismiss(AuthenticatedUser actor, UUID noticeId) {
		if (actor.getTenantId() == null) {
			throw new ApplicationException(ErrorCode.NOT_PERMITTED, Map.of("noticeId", noticeId));
		}

		// Asked first rather than left to the foreign key: a notice that is not there is a 404 with a
		// sentence on it, and an integrity violation is a 500 with none.
		Integer found = jdbc.query("SELECT 1 FROM platform_notices WHERE id = ?",
				rs -> rs.next() ? 1 : null, noticeId);
		if (found == null) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("noticeId", noticeId));
		}

		jdbc.update("""
				INSERT INTO platform_notice_dismissals (notice_id, user_id, tenant_id)
				VALUES (?, ?, ?)
				ON CONFLICT (notice_id, user_id) DO UPDATE SET dismissed_at = now()
				""", noticeId, actor.getUserId(), actor.getTenantId());
	}

	// ---------------------------------------------------------------------

	/**
	 * Whether this reader may take a given notice down: an operator may take down anyone's, and a
	 * temple admin only their own temple's. Read from {@link RolePermissions} rather than from the
	 * role directly, so the answer on a screen and the answer at the endpoint come from the same
	 * policy document.
	 */
	private boolean mayWithdraw(AuthenticatedUser actor, UUID raisedByTenantId) {
		if (actor.getRole() == null) {
			return false;
		}
		var held = RolePermissions.forRole(actor.getRole());
		if (held.contains(Permission.WITHDRAW_ANY_PLATFORM_NOTICE)) {
			return true;
		}
		return held.contains(Permission.RAISE_PLATFORM_NOTICE)
				&& raisedByTenantId != null
				&& raisedByTenantId.equals(actor.getTenantId());
	}

	/** The temple registry is not tenant-scoped; this is the one read of it a temple user makes. */
	private String templeName(UUID tenantId) {
		return jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tenantId);
	}

	private RowMapper<NoticeView> mapper(AuthenticatedUser actor) {
		return (ResultSet rs, int row) -> {
			UUID raisedByTenantId = (UUID) rs.getObject("raised_by_tenant_id");
			OffsetDateTime withdrawnAt = rs.getObject("withdrawn_at", OffsetDateTime.class);
			return new NoticeView(
					rs.getObject("id", UUID.class),
					NoticeSeverity.valueOf(rs.getString("severity")),
					rs.getString("subject"),
					rs.getString("body"),
					rs.getString("raised_by_label"),
					rs.getObject("created_at", OffsetDateTime.class),
					withdrawnAt != null,
					rs.getString("withdrawn_by_label"),
					withdrawnAt,
					rs.getString("withdrawn_reason"),
					raisedByTenantId != null && raisedByTenantId.equals(actor.getTenantId()),
					withdrawnAt == null && mayWithdraw(actor, raisedByTenantId));
		};
	}

	/** Just enough of a notice to decide whether it may be withdrawn, and to describe it if it is. */
	private record Standing(
			UUID raisedByTenantId,
			String raisedByLabel,
			String severity,
			String subject,
			OffsetDateTime withdrawnAt) {
	}
}
