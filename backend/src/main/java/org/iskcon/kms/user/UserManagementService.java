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
 * changes live in {@link RoleChangeService}, which this builds alongside.
 *
 * <p>Every action runs in the acting admin's tenant context, so RLS confines it to their own temple
 * — a user in another temple is simply not found. Creating a person is deliberately not here: a
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
		// No tenant filter in the SQL: RLS scopes it to the acting admin's temple.
		return jdbc.query("""
				SELECT id, full_name, email, phone, role, status, created_at
				FROM users WHERE (CAST(? AS text) IS NULL OR role = CAST(? AS text)) ORDER BY full_name
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
			// RLS hides other temples' users, so an unreadable target is not this temple's to touch.
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

	private Optional<User.Status> currentStatus(UUID userId) {
		List<String> statuses = jdbc.query(
				"SELECT status FROM users WHERE id = ?",
				(rs, rowNum) -> rs.getString("status"), userId);
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
