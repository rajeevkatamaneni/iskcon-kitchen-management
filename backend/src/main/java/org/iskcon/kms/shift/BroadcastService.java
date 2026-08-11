package org.iskcon.kms.shift;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.notification.NotificationRecipient;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.notification.NotificationTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-off shift broadcasts (E6-S7): an immediate free-text update to everyone signed up (optionally
 * the waitlist too). A per-tenant daily cap protects volunteers from a panicking poster. The
 * broadcast and its recipients are recorded for the roster, and the act is audited with its content.
 *
 * <p>Planning (rate check + recording) is one transaction; delivery is a best-effort loop outside it,
 * so a message that can't be queued to one volunteer neither rolls back the record nor stops the rest.
 */
@Service
public class BroadcastService {

	private static final Logger log = LoggerFactory.getLogger(BroadcastService.class);

	private final JdbcTemplate jdbc;
	private final NotificationService notificationService;
	private final TenantSettingsService settings;
	private final AuditService auditService;

	public BroadcastService(JdbcTemplate jdbc, NotificationService notificationService,
			TenantSettingsService settings, AuditService auditService) {
		this.jdbc = jdbc;
		this.notificationService = notificationService;
		this.settings = settings;
		this.auditService = auditService;
	}

	/** Records a broadcast after the rate check; returns the recipients for delivery. */
	@Transactional
	public Plan plan(AuthenticatedUser actor, UUID shiftId, String message, boolean includeWaitlist) {
		Map<String, Object> shift;
		try {
			shift = jdbc.queryForMap("SELECT title, status FROM shifts WHERE id = ?", shiftId);
		} catch (EmptyResultDataAccessException e) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("shiftId", shiftId), e);
		}
		if (!"OPEN".equals(shift.get("status"))) {
			throw new ApplicationException(ErrorCode.SHIFT_NOT_OPEN, Map.of("shiftId", shiftId));
		}

		int limit = settings.volunteerBroadcastDailyLimit();
		Integer today = jdbc.queryForObject("""
				SELECT count(*) FROM shift_broadcasts
				WHERE shift_id = ?
				  AND created_at AT TIME ZONE 'Asia/Kolkata' >= date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata')
				""", Integer.class, shiftId);
		if (today != null && today >= limit) {
			throw new ApplicationException(ErrorCode.BROADCAST_RATE_LIMITED,
					Map.of("shiftId", shiftId, "limit", limit));
		}

		List<UUID> recipients = new ArrayList<>(jdbc.queryForList(
				"SELECT volunteer_user_id FROM shift_signups WHERE shift_id = ? AND released_at IS NULL",
				UUID.class, shiftId));
		if (includeWaitlist) {
			recipients.addAll(jdbc.queryForList("""
					SELECT volunteer_user_id FROM shift_waitlist
					WHERE shift_id = ? AND promoted_at IS NULL AND left_at IS NULL
					""", UUID.class, shiftId));
		}

		UUID broadcastId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO shift_broadcasts (id, tenant_id, shift_id, message, include_waitlist, sent_by)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?)
				""", broadcastId, shiftId, message.trim(), includeWaitlist, actor.getUserId());

		auditService.record(actor, AuditAction.SHIFT_BROADCAST_SENT, AuditEntityType.SHIFT, shiftId,
				null, Map.of("message", message.trim(), "recipients", recipients.size(),
						"includeWaitlist", includeWaitlist), null);

		return new Plan(broadcastId, (String) shift.get("title"), List.copyOf(recipients));
	}

	/** Sends the broadcast to each recipient (best-effort) and records per-recipient status. */
	public int deliver(UUID shiftId, Plan plan) {
		int queued = 0;
		for (UUID userId : plan.recipients()) {
			UUID notificationId = null;
			try {
				notificationId = notificationService.notify(
						NotificationRecipient.user(userId),
						NotificationTemplate.SHIFT_BROADCAST,
						Map.of("title", plan.shiftTitle(), "message", messageFor(plan.broadcastId())),
						null);
				queued++;
			} catch (RuntimeException e) {
				log.warn("Broadcast {} could not be queued to {}: {}", plan.broadcastId(), userId, e.toString());
			}
			jdbc.update("""
					INSERT INTO shift_broadcast_recipients (
						id, tenant_id, broadcast_id, recipient_user_id, notification_id)
					VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
					""", plan.broadcastId(), userId, notificationId);
		}
		return queued;
	}

	private String messageFor(UUID broadcastId) {
		return jdbc.queryForObject("SELECT message FROM shift_broadcasts WHERE id = ?", String.class, broadcastId);
	}

	/** A recorded broadcast ready to deliver. */
	public record Plan(UUID broadcastId, String shiftTitle, List<UUID> recipients) {
	}
}
