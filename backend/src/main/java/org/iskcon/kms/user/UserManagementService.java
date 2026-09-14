package org.iskcon.kms.user;

import java.time.OffsetDateTime;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Managing the people in a temple (E1-S12): listing them, and disabling or restoring them. Role
 * changes are not here and are not anywhere under {@code /api/v1/users}: a temple role is granted
 * and changed by editing the staff record ({@code staff/StaffEmploymentService}), which is the
 * only door hiring leaves open.
 *
 * <p>Every action runs in the acting admin's tenant context, and every read here also names that
 * temple in its SQL, so a user in another temple is simply not found. RLS alone is not enough for
 * this table: its read policy also shows the acting admin their own accounts at other temples
 * (T-190, and {@link #listUsers}). Creating a person is deliberately not here: a
 * devotee registers themselves (E1-S17) and a member of staff is hired (E6-S8), which is also the
 * only act that grants a temple role. Disabling never deletes — history and audit references must
 * survive.
 */
@Service
public class UserManagementService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public UserManagementService(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	/**
	 * The temple's people, optionally narrowed to one role.
	 *
	 * <p>The narrowing exists because the temple's two registers are read separately: devotees are
	 * everyone who registered themselves (VOLUNTEER), and staff are the people it employs. Asking
	 * the API for the one you want beats fetching both and hiding half in the browser.
	 */
	@Transactional(readOnly = true)
	public List<UserSummary> listUsers(User.Role role) {
		// The temple is named in the SQL, because RLS alone does not confine this table to it (T-190).
		// This used to say "no tenant filter: RLS scopes it to the acting admin's temple", which is true
		// of every other table and not of users. Its read policy also admits the caller's own accounts
		// at other temples (firebase_uid = app.auth_uid, V2 — how sign-in finds a person's memberships),
		// and the filter leaves that uid set for the whole request. So an administrator who is also a
		// devotee somewhere else saw that account in this temple's register, listed as one of its own
		// devotees, where it could be picked to hire or to put in charge of a kitchen.
		return jdbc.query("""
				SELECT id, full_name, email, phone, role, status, created_at
				FROM users
				WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				  AND (CAST(? AS text) IS NULL OR role = CAST(? AS text))
				ORDER BY full_name
				""", (rs, rowNum) -> new UserSummary(
						rs.getObject("id", UUID.class),
						rs.getString("full_name"),
						rs.getString("email"),
						rs.getString("phone"),
						rs.getString("role"),
						rs.getString("status"),
						rs.getObject("created_at", OffsetDateTime.class).toInstant()),
				role == null ? null : role.name(), role == null ? null : role.name());
	}

	@Transactional
	public void setStatus(AuthenticatedUser actor, UUID targetUserId, String requestedStatus) {
		User.Status newStatus = parseStatus(requestedStatus);

		if (newStatus == User.Status.DISABLED && targetUserId.equals(actor.getUserId())) {
			// Disabling yourself locks you out of your own temple.
			throw new ApplicationException(
					ErrorCode.CANNOT_DISABLE_SELF, Map.of("targetUserId", targetUserId));
		}

		Optional<User.Status> current = currentStatus(targetUserId);
		if (current.isEmpty()) {
			// Not an account at this temple — somebody else's temple, or the admin's own account at
			// another one — so it is not this temple's to touch.
			throw new ApplicationException(
					ErrorCode.RESOURCE_NOT_FOUND, Map.of("targetUserId", targetUserId));
		}
		if (current.get() == newStatus) {
			return;
		}

		jdbc.update("UPDATE users SET status = ?, updated_at = now() WHERE id = ?",
				newStatus.name(), targetUserId);

		auditService.record(
				actor,
				newStatus == User.Status.DISABLED ? AuditAction.USER_DISABLED : AuditAction.USER_ENABLED,
				AuditEntityType.USER, targetUserId,
				Map.of("status", current.get().name()),
				Map.of("status", newStatus.name()),
				null);
	}

	/**
	 * The target's status, if the target is an account at this temple.
	 *
	 * <p>The temple is named in the query (T-190). The id comes from the request path, and the read
	 * policy on {@code users} also shows the acting admin their own accounts at other temples
	 * ({@code firebase_uid = app.auth_uid}, V2). Trusting RLS alone, "disable" on the admin's own
	 * account elsewhere found it, the UPDATE then matched nothing because writes are temple-only, and
	 * the audit entry was written anyway — a record saying this temple disabled somebody who was never
	 * its own.
	 */
	private Optional<User.Status> currentStatus(UUID userId) {
		List<String> statuses = jdbc.query("""
				SELECT status FROM users
				WHERE id = ? AND tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", (rs, rowNum) -> rs.getString("status"), userId);
		return statuses.stream().findFirst().map(User.Status::valueOf);
	}

	private User.Role parseRole(String role) {
		try {
			return User.Role.valueOf(role);
		} catch (IllegalArgumentException e) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED, Map.of("field", "role", "value", role), e);
		}
	}

	private User.NotificationChannel parseChannel(String channel) {
		if (channel == null || channel.isBlank()) {
			return User.NotificationChannel.WHATSAPP;
		}
		try {
			return User.NotificationChannel.valueOf(channel);
		} catch (IllegalArgumentException e) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED, Map.of("field", "preferredChannel", "value", channel), e);
		}
	}

	private User.Status parseStatus(String status) {
		try {
			return User.Status.valueOf(status);
		} catch (IllegalArgumentException e) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED, Map.of("field", "status", "value", status), e);
		}
	}
}
