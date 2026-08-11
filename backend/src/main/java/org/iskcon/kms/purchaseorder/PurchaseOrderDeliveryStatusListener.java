package org.iskcon.kms.purchaseorder;

import java.util.List;
import java.util.UUID;
import org.iskcon.kms.notification.DeliveryStatus;
import org.iskcon.kms.notification.NotificationStatusChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reflects a WhatsApp delivery outcome (E5-S7) onto the purchase order it belongs to. Listens for the
 * generic {@link NotificationStatusChanged} the notification service publishes when a delivery webhook
 * lands, finds the PO whose WHATSAPP_SENT event links that notification, and records the outcome on
 * the trail — flagging the vendor for a phone recheck when delivery failed.
 *
 * <p>Runs synchronously on the webhook thread within its tenant context, so it reads and writes under
 * ordinary RLS. It never lets a bookkeeping failure escape into the webhook — a delivery callback must
 * always be acknowledged.
 */
@Component
public class PurchaseOrderDeliveryStatusListener {

	private static final Logger log = LoggerFactory.getLogger(PurchaseOrderDeliveryStatusListener.class);

	private final JdbcTemplate jdbc;
	private final PurchaseOrderService purchaseOrders;

	public PurchaseOrderDeliveryStatusListener(JdbcTemplate jdbc, PurchaseOrderService purchaseOrders) {
		this.jdbc = jdbc;
		this.purchaseOrders = purchaseOrders;
	}

	@EventListener
	public void onDeliveryStatus(NotificationStatusChanged event) {
		try {
			List<UUID> pos = jdbc.queryForList("""
					SELECT po_id FROM po_events
					WHERE notification_id = ? AND event_type = 'WHATSAPP_SENT'
					ORDER BY created_at DESC LIMIT 1
					""", UUID.class, event.notificationId());
			if (pos.isEmpty()) {
				return; // Not a PO send — nothing for this listener to do.
			}
			UUID poId = pos.get(0);

			if (event.status() == DeliveryStatus.DELIVERED) {
				purchaseOrders.recordEvent(poId, "WHATSAPP_DELIVERED",
						"The vendor's WhatsApp confirmed delivery", null);
			} else if (event.status() == DeliveryStatus.FAILED) {
				purchaseOrders.recordEvent(poId, "WHATSAPP_FAILED",
						"WhatsApp delivery failed — download the PO and share it manually", null);
				// Flag the vendor so the number is rechecked before the next send.
				jdbc.update("""
						UPDATE vendors SET whatsapp_reachable = false, updated_at = now()
						WHERE id = (SELECT vendor_id FROM purchase_orders WHERE id = ?)
						""", poId);
			}
		} catch (RuntimeException e) {
			// A delivery webhook must always be acknowledged; trail bookkeeping never breaks it.
			log.warn("Could not reflect delivery status onto the PO trail for notification {}",
					event.notificationId(), e);
		}
	}
}
