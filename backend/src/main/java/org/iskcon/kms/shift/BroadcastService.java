package org.iskcon.kms.shift;

import java.time.LocalTime;
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
import org.iskcon.kms.tenancy.TempleClock;
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

	private final TempleClock clock;
	private final JdbcTemplate jdbc;
	private final NotificationService notificationService;
	private final TenantSettingsService settings;
	private final AuditService auditService;

	public BroadcastService(JdbcTemplate jdbc, NotificationService notificationService,
			TenantSettingsService settings, AuditService auditService, TempleClock clock) {
		this.clock = clock;
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
		// A daily cap is a cap on the temple's day. It was the string 'Asia/Kolkata' twice inside the
		// SQL, which is the one place a hard-coded zone cannot be found by looking at imports.
		String zone = clock.zone().getId();
		Integer today = jdbc.queryForObject("""
				SELECT count(*) FROM shift_broadcasts
				WHERE shift_id = ?
				  AND created_at AT TIME ZONE ? >= date_trunc('day', now() AT TIME ZONE ?)
				""", Integer.class, shiftId, zone, zone);
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

	/**
	 * Records the notice that a shift's times changed under people already signed up (D-27, answer
	 * 6), and returns who to tell. Joins the caller's transaction, so a save that rolls back leaves no
	 * record of a notice that was never sent; {@link #deliver} is the caller's to run after commit.
	 *
	 * <p><strong>The approved {@code shift_broadcast} message, not a template of its own.</strong>
	 * Rajeev: <em>"Option 1 : Approved"</em> — each signed-up volunteer gets the coordinator message
	 * already approved by Meta, with the coordinator text {@link #timesChangedMessage}. A new template
	 * would need Meta's approval before the first time change could be told at all.
	 *
	 * <p><strong>Signed-up volunteers only.</strong> The waitlist holds no place on these hours, and
	 * a waitlister promoted later is told the shift's times by the promotion message itself.
	 *
	 * <p><strong>Not refused by the daily cap.</strong> The cap in {@link #plan} protects volunteers
	 * from a coordinator sending message after message. This one is not a coordinator's choice to
	 * send: it is what saving the new times means. Refusing it would either refuse the save or save
	 * without telling the people whose day just changed, and D-27 forbids the second. It is still
	 * recorded as a broadcast, so the roster shows it with its delivery status beside the others, and
	 * audited like one.
	 */
	@Transactional
	public Plan planTimesChanged(AuthenticatedUser actor, UUID shiftId, LocalTime startTime, LocalTime endTime) {
		String title;
		try {
			title = jdbc.queryForObject("SELECT title FROM shifts WHERE id = ?", String.class, shiftId);
		} catch (EmptyResultDataAccessException e) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("shiftId", shiftId), e);
		}
		List<UUID> recipients = List.copyOf(jdbc.queryForList(
				"SELECT volunteer_user_id FROM shift_signups WHERE shift_id = ? AND released_at IS NULL",
				UUID.class, shiftId));
		String message = timesChangedMessage(startTime, endTime);

		UUID broadcastId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO shift_broadcasts (id, tenant_id, shift_id, message, include_waitlist, sent_by)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, false, ?)
				""", broadcastId, shiftId, message, actor.getUserId());

		auditService.record(actor, AuditAction.SHIFT_BROADCAST_SENT, AuditEntityType.SHIFT, shiftId,
				null, Map.of("message", message, "recipients", recipients.size(),
						"includeWaitlist", false), null);

		return new Plan(broadcastId, title, recipients);
	}

	/**
	 * <em>"The times changed to 09:00 to 13:00."</em> — the coordinator text of the times-changed
	 * notice, exactly as D-27 answer 6 words it. Never the word "moved": a meal shift cannot move to
	 * another day or meal (answers 3 and 4), so only its times can change, and a volunteer told their
	 * shift "moved" would reasonably go looking for a different day.
	 *
	 * <p>Each time is printed through {@link ShiftWindow#clock}, the formatter every other shift
	 * message uses. The "(next day)" that {@link ShiftWindow#describe} adds to an overnight window is
	 * not added: the sentence is Rajeev's, word for word.
	 */
	public static String timesChangedMessage(LocalTime startTime, LocalTime endTime) {
		return "The times changed to " + ShiftWindow.clock(startTime) + " to " + ShiftWindow.clock(endTime) + ".";
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
