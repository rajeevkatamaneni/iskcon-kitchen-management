package org.iskcon.kms.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.jobs.KmsJob;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.user.User.NotificationChannel;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one way anything in the system sends a message — {@code notify(...)}. It resolves who the
 * message is for and on which channel, records the intent, and hands the actual sending to a
 * background job so nothing sends inline on a request thread.
 *
 * <p>Consent is honoured here: a user who has not agreed to be contacted (E1-S8) is recorded as
 * SUPPRESSED and nothing is sent. A raw send to a vendor carries no such gate — that is a business
 * contact for a specific transaction, not personal communication a person opted into.
 *
 * <p>The scheduler is injected as an {@link ObjectProvider} because it lives only where the worker
 * runs; a caller that reaches enqueuing without one is a misconfiguration, and says so loudly.
 */
@Service
public class NotificationService {

	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

	static final String NOTIFICATION_ID_KEY = "kms.notificationId";

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;
	private final ObjectProvider<Scheduler> scheduler;

	public NotificationService(
			JdbcTemplate jdbc, ObjectMapper objectMapper, ObjectProvider<Scheduler> scheduler) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
		this.scheduler = scheduler;
	}

	/**
	 * Queues a message. Returns the notification's id.
	 *
	 * @param channelOverride a specific channel to prefer, or null to use the user's preference (or,
	 *     for a vendor, whatever address is available)
	 */
	@Transactional
	public UUID notify(
			NotificationRecipient recipient,
			NotificationTemplate template,
			Map<String, Object> params,
			NotificationChannel channelOverride) {

		Resolved resolved = resolve(recipient, channelOverride);

		if (resolved.suppressed()) {
			// Recorded so the absence of a message is explainable, but not sent.
			UUID id = insert(resolved, template, params, "SUPPRESSED");
			log.info("Notification {} suppressed — recipient has not consented to be contacted", id);
			return id;
		}

		UUID id = insert(resolved, template, params, "PENDING");
		enqueueSend(id);
		return id;
	}

	private Resolved resolve(NotificationRecipient recipient, NotificationChannel channelOverride) {
		if (recipient.isUser()) {
			return resolveUser(recipient.userId(), channelOverride);
		}
		return resolveVendor(recipient, channelOverride);
	}

	private Resolved resolveUser(UUID userId, NotificationChannel channelOverride) {
		List<Resolved> rows = jdbc.query("""
				SELECT full_name, email, phone, preferred_channel, contact_consent_at
				FROM users WHERE id = ?
				""", (rs, rowNum) -> {
			NotificationChannel preferred = channelOverride != null
					? channelOverride
					: NotificationChannel.valueOf(rs.getString("preferred_channel"));
			boolean consented = rs.getObject("contact_consent_at") != null;
			return new Resolved(
					userId,
					rs.getString("full_name"),
					rs.getString("phone"),
					rs.getString("email"),
					preferred,
					!consented);
		}, userId);

		if (rows.isEmpty()) {
			throw new ApplicationException(
					ErrorCode.RESOURCE_NOT_FOUND, Map.of("recipientUserId", userId));
		}
		return rows.get(0);
	}

	private Resolved resolveVendor(NotificationRecipient recipient, NotificationChannel channelOverride) {
		NotificationChannel preferred = channelOverride != null
				? channelOverride
				: (recipient.rawPhone() != null ? NotificationChannel.WHATSAPP : NotificationChannel.EMAIL);
		String label = "Vendor " + (recipient.rawPhone() != null ? recipient.rawPhone() : recipient.rawEmail());
		return new Resolved(
				null, label, recipient.rawPhone(), recipient.rawEmail(), preferred, false);
	}

	private UUID insert(
			Resolved r, NotificationTemplate template, Map<String, Object> params, String status) {

		if (r.toPhone() == null && r.toEmail() == null) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED, Map.of("field", "recipient", "reason", "no contact address"));
		}

		UUID id = UUID.randomUUID();
		String paramsJson = toJson(params);

		jdbc.update(con -> {
			PreparedStatement ps = con.prepareStatement("""
					INSERT INTO notifications (
						id, tenant_id, recipient_user_id, recipient_label, to_phone, to_email,
						template, params, preferred_channel, status)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						?, ?, ?, ?, ?, ?, ?, ?)
					""");
			ps.setObject(1, id);
			ps.setObject(2, r.userId());
			ps.setString(3, r.label());
			ps.setString(4, r.toPhone());
			ps.setString(5, r.toEmail());
			ps.setString(6, template.name());
			setJson(ps, 7, paramsJson);
			ps.setString(8, r.preferredChannel().name());
			ps.setString(9, status);
			return ps;
		});

		return id;
	}

	private void enqueueSend(UUID notificationId) {
		Scheduler quartz = scheduler.getIfAvailable();
		if (quartz == null) {
			throw new IllegalStateException(
					"No scheduler available to enqueue a send — is this running without the worker enabled?");
		}

		UUID tenantId = TenantContext.get().orElseThrow(() ->
				new IllegalStateException("notify() must run within a tenant context"));

		JobDetail job = JobBuilder.newJob(SendNotificationJob.class)
				.withIdentity("send-" + notificationId)
				.usingJobData(NOTIFICATION_ID_KEY, notificationId.toString())
				.usingJobData(KmsJob.TENANT_KEY, tenantId.toString())
				.requestRecovery()
				.build();

		Trigger trigger = TriggerBuilder.newTrigger().forJob(job).startNow().build();

		try {
			quartz.scheduleJob(job, trigger);
		} catch (org.quartz.SchedulerException e) {
			throw new ApplicationException(ErrorCode.UNEXPECTED_FAILURE, Map.of(), e);
		}
	}

	private String toJson(Map<String, Object> params) {
		if (params == null || params.isEmpty()) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(params);
		} catch (JsonProcessingException e) {
			throw new ApplicationException(ErrorCode.UNEXPECTED_FAILURE, Map.of(), e);
		}
	}

	private void setJson(PreparedStatement ps, int index, String json) throws SQLException {
		if (json == null) {
			ps.setNull(index, Types.OTHER);
		} else {
			ps.setObject(index, json, Types.OTHER);
		}
	}

	private record Resolved(
			UUID userId, String label, String toPhone, String toEmail,
			NotificationChannel preferredChannel, boolean suppressed) {
	}
}
