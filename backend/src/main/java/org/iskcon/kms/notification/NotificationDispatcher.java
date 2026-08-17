package org.iskcon.kms.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.iskcon.kms.user.User.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a queued notification through the fallback cascade and records what happened.
 *
 * <p>The cascade is the preferred channel, then SMS, then email (SYSTEM_DESIGN.md §6): each is tried
 * in turn until one accepts the message, and every attempt — sent, failed, or skipped for want of an
 * address — is written down, so "a forced WhatsApp failure falls back to SMS" leaves a legible trail.
 *
 * <p>Idempotent: a notification already in a terminal state is left alone, so a job retry or a
 * duplicate enqueue does not send twice.
 */
@Service
public class NotificationDispatcher {

	private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

	private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;
	private final MeterRegistry meterRegistry;
	private final Map<NotificationChannel, ChannelAdapter> adapters;

	public NotificationDispatcher(
			JdbcTemplate jdbc, ObjectMapper objectMapper, MeterRegistry meterRegistry,
			List<ChannelAdapter> adapterList) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
		this.meterRegistry = meterRegistry;
		this.adapters = adapterList.stream()
				.collect(Collectors.toUnmodifiableMap(ChannelAdapter::channel, Function.identity()));
	}

	/** Sends {@code notificationId}, which is expected to be visible in the current tenant context. */
	@Transactional
	public void dispatch(UUID notificationId) {
		Notification n = load(notificationId);
		if (n == null) {
			log.warn("Notification {} not found in context; nothing to dispatch", notificationId);
			return;
		}
		if (isTerminal(n.status())) {
			// Already sent, failed, or suppressed — a retry or duplicate enqueue must not resend.
			return;
		}

		NotificationTemplate template = NotificationTemplate.valueOf(n.template());
		OutboundMessage message =
				new OutboundMessage(template, n.params(), template.render(n.params()));

		for (NotificationChannel channel : cascade(n.preferredChannel())) {
			String address = addressFor(channel, n);
			if (address == null) {
				recordAttempt(n.id(), channel, "SKIPPED", null, "no address for this channel");
				continue;
			}

			SendResult result = adapters.get(channel).send(address, message);
			if (result.sent()) {
				recordAttempt(n.id(), channel, "SENT", result.providerMessageId(), null);
				markSent(n.id(), channel, result.providerMessageId());
				return;
			}
			recordAttempt(n.id(), channel, "FAILED", null, result.detail());
		}

		markFailed(n.id());
	}

	/** Preferred channel first, then SMS, then email — with duplicates removed. */
	private Set<NotificationChannel> cascade(NotificationChannel preferred) {
		Set<NotificationChannel> order = new LinkedHashSet<>();
		order.add(preferred);
		order.add(NotificationChannel.SMS);
		order.add(NotificationChannel.EMAIL);
		return order;
	}

	private String addressFor(NotificationChannel channel, Notification n) {
		return switch (channel) {
			case WHATSAPP, SMS -> n.toPhone();
			case EMAIL -> n.toEmail();
		};
	}

	private boolean isTerminal(String status) {
		return !"PENDING".equals(status);
	}

	private void markSent(UUID id, NotificationChannel channel, String providerMessageId) {
		jdbc.update(
				"UPDATE notifications SET status = 'SENT', final_channel = ?, "
						+ "provider_message_id = ?, updated_at = now() WHERE id = ?",
				channel.name(), providerMessageId, id);
		meterRegistry.counter("kms.notifications.sent", "channel", channel.name()).increment();
	}

	private void markFailed(UUID id) {
		jdbc.update(
				"UPDATE notifications SET status = 'FAILED', updated_at = now() WHERE id = ?", id);
		meterRegistry.counter("kms.notifications.failed").increment();
	}

	private void recordAttempt(
			UUID notificationId, NotificationChannel channel, String outcome,
			String providerMessageId, String detail) {
		jdbc.update("""
				INSERT INTO notification_attempts (
					notification_id, tenant_id, channel, outcome, provider_message_id, detail)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?)
				""", notificationId, channel.name(), outcome, providerMessageId, detail);
	}

	private Notification load(UUID id) {
		List<Notification> rows = jdbc.query("""
				SELECT id, status, template, params, preferred_channel, to_phone, to_email
				FROM notifications WHERE id = ?
				""", (rs, rowNum) -> new Notification(
						rs.getObject("id", UUID.class),
						rs.getString("status"),
						rs.getString("template"),
						parseParams(rs.getString("params")),
						NotificationChannel.valueOf(rs.getString("preferred_channel")),
						rs.getString("to_phone"),
						rs.getString("to_email")),
				id);
		return rows.isEmpty() ? null : rows.get(0);
	}

	private Map<String, Object> parseParams(String json) {
		if (json == null) {
			return new HashMap<>();
		}
		try {
			return objectMapper.readValue(json, JSON_MAP);
		} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new IllegalStateException("Unreadable notification params", e);
		}
	}

	private record Notification(
			UUID id, String status, String template, Map<String, Object> params,
			NotificationChannel preferredChannel, String toPhone, String toEmail) {
	}
}
