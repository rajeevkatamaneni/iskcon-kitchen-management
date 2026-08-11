package org.iskcon.kms.notification;

import java.util.List;
import java.util.UUID;
import org.iskcon.kms.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Applies a provider's delivery status to the message it belongs to.
 *
 * <p>The webhook arrives unauthenticated and knows the message only by the provider's id, before any
 * tenant is known — so the lookup runs under the narrow {@code app.webhook_message_id} escape (V7),
 * finds the one row, reads its tenant, and only then performs the update through ordinary tenant
 * isolation. The update is written to move status only out of a non-terminal state, so a duplicate
 * webhook — Meta retries them — changes nothing the second time.
 */
@Service
public class NotificationDeliveryService {

	private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);

	private final JdbcTemplate jdbc;
	private final ApplicationEventPublisher events;

	public NotificationDeliveryService(JdbcTemplate jdbc, ApplicationEventPublisher events) {
		this.jdbc = jdbc;
		this.events = events;
	}

	public void applyStatus(String providerMessageId, DeliveryStatus status) {
		if (status == DeliveryStatus.OTHER || providerMessageId == null) {
			// A status we don't model (e.g. "sent") or a payload without an id — nothing to do.
			return;
		}

		Target target = lookup(providerMessageId);
		if (target == null) {
			// No message with that id — a webhook for something we did not send, or already gone.
			// Ignoring is the idempotent response.
			log.warn("Delivery webhook for unknown provider message id");
			return;
		}

		TenantContext.set(target.tenantId());
		applyTransition(target.id(), status);
	}

	private void publishIfChanged(UUID id, DeliveryStatus status, int changed) {
		if (changed > 0) {
			// Let features that originated the message react (e.g. a PO reflecting delivery on its
			// trail). Synchronous, on this thread, within the tenant context already set.
			events.publishEvent(new NotificationStatusChanged(id, status));
		}
	}

	/** Finds the message by provider id under the webhook RLS escape, before any tenant is known. */
	private Target lookup(String providerMessageId) {
		TenantContext.setWebhookMessageId(providerMessageId);
		try {
			List<Target> rows = jdbc.query(
					"SELECT id, tenant_id FROM notifications WHERE provider_message_id = ?",
					(rs, rowNum) -> new Target(
							rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class)),
					providerMessageId);
			return rows.isEmpty() ? null : rows.get(0);
		} finally {
			TenantContext.clearWebhookMessageId();
		}
	}

	private void applyTransition(UUID id, DeliveryStatus status) {
		String newStatus = status == DeliveryStatus.DELIVERED ? "DELIVERED" : "FAILED";
		// Only advance from a non-terminal state: a repeat webhook, or one arriving after the message
		// already reached a terminal status, updates nothing.
		int changed = jdbc.update(
				"UPDATE notifications SET status = ?, updated_at = now() "
						+ "WHERE id = ? AND status IN ('PENDING', 'SENT')",
				newStatus, id);

		if (changed == 0) {
			log.debug("Delivery status {} for {} was a no-op (already terminal)", newStatus, id);
		}
		publishIfChanged(id, status, changed);
	}

	private record Target(UUID id, UUID tenantId) {
	}
}
