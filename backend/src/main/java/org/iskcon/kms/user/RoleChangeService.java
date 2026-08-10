package org.iskcon.kms.user;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changes a user's role — the one Epic 1 action with a real prior state, and so the exemplar of
 * before/after auditing. It is deliberately the seed of user management (E1-S12) rather than the
 * whole of it: no UI, one endpoint.
 *
 * <p>It is a privilege-escalation surface, so it is guarded rather than trusting the caller:
 *
 * <ol>
 *   <li><strong>No self-change.</strong> Prevents both self-escalation and an administrator
 *       locking themselves out of their own temple.
 *   <li><strong>No promotion to super-admin.</strong> That role is minted only by provisioning; a
 *       temple admin able to grant it could mint a platform operator.
 *   <li><strong>No cross-tenant change.</strong> RLS already makes another temple's user
 *       invisible — an unreadable target is treated as absent — and a test asserts it rather than
 *       trusting the assertion.
 *   <li><strong>Everything is audited, including refusals.</strong> A blocked escalation is
 *       exactly what someone reviewing the log is looking for, so a refused attempt is recorded
 *       (via {@link AuditService#recordSeparately}, so the record survives the 403 that follows).
 * </ol>
 */
@Service
public class RoleChangeService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public RoleChangeService(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	/**
	 * Changes {@code targetUserId}'s role to {@code requestedRole}, or refuses and records why.
	 *
	 * <p>Transactional so the successful path — the UPDATE and its {@code ROLE_CHANGED} event —
	 * commit together or not at all. A refusal writes its audit on a separate transaction and then
	 * throws, so the outer rollback (which discards only reads) does not take the record with it.
	 */
	@Transactional
	public void changeRole(AuthenticatedUser actor, UUID targetUserId, String requestedRole) {
		User.Role newRole = parseRole(requestedRole);

		// Guard 1 — self. Checked first: it needs no lookup and holds whatever the target's state.
		if (targetUserId.equals(actor.getUserId())) {
			rejectSeparately(actor, targetUserId, actor.getRole(), newRole,
					ErrorCode.CANNOT_CHANGE_OWN_ROLE, "attempted to change their own role");
		}

		// Guard 3 — cross-tenant. RLS hides other temples' users, so an unreadable target is one
		// that belongs to another temple or does not exist. Either way it is not found here.
		Optional<User.Role> existing = currentRole(targetUserId);
		if (existing.isEmpty()) {
			rejectSeparately(actor, targetUserId, null, newRole,
					ErrorCode.RESOURCE_NOT_FOUND, "target not found in the actor's temple");
		}
		User.Role from = existing.orElseThrow();

		// Guard 2 — never mint a platform operator through user management.
		if (newRole == User.Role.SUPER_ADMIN) {
			rejectSeparately(actor, targetUserId, from, newRole,
					ErrorCode.CANNOT_ASSIGN_SUPER_ADMIN, "attempted to assign SUPER_ADMIN");
		}

		// A change to the role someone already holds is a no-op, not an event worth recording.
		if (from == newRole) {
			return;
		}

		int updated = jdbc.update(
				"UPDATE users SET role = ?, updated_at = now() WHERE id = ?",
				newRole.name(), targetUserId);

		if (updated != 1) {
			// Visible a moment ago, gone now — treat as absent rather than guess. RLS also
			// guarantees this UPDATE could not have touched another tenant's row.
			throw new ApplicationException(
					ErrorCode.RESOURCE_NOT_FOUND, Map.of("targetUserId", targetUserId));
		}

		auditService.record(actor, AuditAction.ROLE_CHANGED, AuditEntityType.USER, targetUserId,
				Map.of("role", from.name()), Map.of("role", newRole.name()), null);
	}

	/**
	 * Records the refused attempt on its own transaction, then throws. The audit write must not be
	 * part of the transaction that is about to roll back, or the evidence of the refusal would roll
	 * back with it — defeating the point of recording refusals at all.
	 */
	private void rejectSeparately(
			AuthenticatedUser actor, UUID targetId, User.Role currentRole, User.Role attemptedRole,
			ErrorCode code, String reason) {

		auditService.recordSeparately(
				actor, AuditAction.ROLE_CHANGE_REJECTED, AuditEntityType.USER, targetId,
				currentRole == null ? null : Map.of("role", currentRole.name()),
				Map.of("role", attemptedRole.name()),
				reason);

		throw new ApplicationException(
				code, Map.of("targetUserId", targetId, "attemptedRole", attemptedRole.name()));
	}

	/**
	 * The target's current role, or empty if RLS does not show the row — which is the cross-tenant
	 * guard doing its work.
	 */
	private Optional<User.Role> currentRole(UUID userId) {
		List<String> roles = jdbc.query(
				"SELECT role FROM users WHERE id = ?",
				(rs, rowNum) -> rs.getString("role"),
				userId);

		return roles.stream().findFirst().map(User.Role::valueOf);
	}

	private User.Role parseRole(String requested) {
		try {
			return User.Role.valueOf(requested);
		} catch (IllegalArgumentException e) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED, Map.of("field", "role", "value", requested), e);
		}
	}
}
