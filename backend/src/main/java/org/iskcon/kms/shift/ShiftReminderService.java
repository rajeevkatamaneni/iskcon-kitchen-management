package org.iskcon.kms.shift;

import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Sends a single scheduled shift reminder (E6-S6) — the worker-side core the reminder job calls.
 *
 * <p>Idempotent, as every job must be: it claims the (signup × offset) row first, and only sends if
 * it won the claim, so a retried or recovered job never reminds twice. A reminder for a spot that has
 * since been released, or a shift since cancelled, is skipped. The notification's id is recorded so
 * the poster's roster can show delivery status.
 */
@Service
public class ShiftReminderService {

	private static final Logger log = LoggerFactory.getLogger(ShiftReminderService.class);

	private final JdbcTemplate jdbc;
	private final NotificationService notificationService;

	public ShiftReminderService(JdbcTemplate jdbc, NotificationService notificationService) {
		this.jdbc = jdbc;
		this.notificationService = notificationService;
	}

	@Transactional
	public void sendReminder(UUID signupId, int offsetMinutes) {
		Map<String, Object> row;
		try {
			row = jdbc.queryForMap("""
					SELECT ss.shift_id, ss.volunteer_user_id, ss.released_at, s.status, s.title,
						   s.shift_date, s.start_time, s.end_time, s.location
					FROM shift_signups ss JOIN shifts s ON s.id = ss.shift_id
					WHERE ss.id = ?
					""", signupId);
		} catch (EmptyResultDataAccessException e) {
			return; // signup gone
		}
		if (row.get("released_at") != null || !"OPEN".equals(row.get("status"))) {
			return; // released, or shift cancelled — no reminder
		}
		UUID shiftId = (UUID) row.get("shift_id");
		UUID volunteerUserId = (UUID) row.get("volunteer_user_id");

		// Claim the (signup, offset) slot; if another run already claimed it, do nothing.
		UUID reminderId = UUID.randomUUID();
		List<UUID> claimed = jdbc.query("""
				INSERT INTO shift_reminders (id, tenant_id, shift_id, signup_id, offset_minutes)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
				ON CONFLICT (signup_id, offset_minutes) DO NOTHING
				RETURNING id
				""", (rs, n) -> rs.getObject("id", UUID.class), reminderId, shiftId, signupId, offsetMinutes);
		if (claimed.isEmpty()) {
			return; // already sent
		}

		String temple = templeName();
		String location = row.get("location") != null ? row.get("location").toString() : temple;
		String time = row.get("start_time") + "–" + row.get("end_time");
		UUID notificationId = notificationService.notify(
				NotificationRecipient.user(volunteerUserId),
				NotificationTemplate.VOLUNTEER_SHIFT_REMINDER,
				Map.of("title", str(row.get("title")), "date", str(row.get("shift_date")),
						"time", time, "location", location, "temple", temple),
				null);
		jdbc.update("UPDATE shift_reminders SET notification_id = ? WHERE id = ?", notificationId, reminderId);
		log.debug("Sent {}-minute reminder for signup {}", offsetMinutes, signupId);
	}

	private String templeName() {
		try {
			return jdbc.queryForObject("""
					SELECT name FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""", String.class);
		} catch (RuntimeException e) {
			return "the temple";
		}
	}

	private static String str(Object o) {
		return o == null ? "" : o.toString();
	}
}
