package org.iskcon.kms.purchaseorder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.notification.NotificationRecipient;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.notification.NotificationTemplate;
import org.iskcon.kms.user.User.NotificationChannel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sending a purchase order to its vendor on WhatsApp (E5-S7) through the E1-S10 notification service —
 * ordering the way Indian vendors actually communicate. A draft is sent first (transitioned to SENT
 * with its sheet generated) as part of delivering it; a received or cancelled order can't go out.
 *
 * <p>The send is recorded on the PO's trail linked to the notification, so the provider's delivery
 * webhook can reflect the outcome back onto the trail and flag an unreachable vendor
 * ({@link PurchaseOrderDeliveryStatusListener}). A short rate guard stops an accidental send-loop
 * from spamming a vendor; a resend after that window is allowed and audited like the first.
 */
@Service
public class PurchaseOrderDeliveryService {

	private static final int RATE_LIMIT_SECONDS = 120;
	private static final int SUMMARY_ITEMS = 3;

	private final JdbcTemplate jdbc;
	private final PurchaseOrderService purchaseOrders;
	private final NotificationService notificationService;
	private final AuditService auditService;

	public PurchaseOrderDeliveryService(
			JdbcTemplate jdbc, PurchaseOrderService purchaseOrders,
			NotificationService notificationService, AuditService auditService) {
		this.jdbc = jdbc;
		this.purchaseOrders = purchaseOrders;
		this.notificationService = notificationService;
		this.auditService = auditService;
	}

	/** Sends (or resends) the PO to its vendor on WhatsApp; returns the notification's id. */
	@Transactional
	public UUID sendViaWhatsApp(AuthenticatedUser actor, UUID poId) {
		PurchaseOrderDetailView po = purchaseOrders.get(poId);
		PoStatus status = po.order().status();
		if (status == PoStatus.DRAFT) {
			// Sending a draft transitions it to SENT (and generates its sheet) as part of delivering.
			purchaseOrders.send(actor, poId);
			po = purchaseOrders.get(poId);
		} else if (status == PoStatus.RECEIVED || status == PoStatus.CANCELLED) {
			throw new ApplicationException(ErrorCode.PO_NOT_SENDABLE, Map.of("purchaseOrderId", poId));
		}

		guardRate(poId);

		Map<String, Object> vendor = jdbc.queryForMap(
				"SELECT name, phone FROM vendors WHERE id = ?", po.order().vendorId());
		String vendorName = (String) vendor.get("name");
		String phone = (String) vendor.get("phone");

		Map<String, Object> params = new HashMap<>();
		params.put("poNumber", po.order().poNumber());
		params.put("vendor", vendorName);
		params.put("summary", summarize(po.lines()));
		latestReadySheet(poId).ifPresent(docId -> params.put("documentId", docId.toString()));

		UUID notificationId = notificationService.notify(
				NotificationRecipient.vendor(phone, null),
				NotificationTemplate.PO_DELIVERY, params, NotificationChannel.WHATSAPP);

		purchaseOrders.recordEvent(poId, "WHATSAPP_SENT",
				"Sent to " + vendorName + " on WhatsApp", actor, notificationId);
		auditService.record(actor, AuditAction.PO_WHATSAPP_SENT, AuditEntityType.PURCHASE_ORDER, poId,
				null, Map.of("poNumber", po.order().poNumber(), "channel", "WHATSAPP", "to", phone), null);

		return notificationId;
	}

	private void guardRate(UUID poId) {
		Integer recent = jdbc.queryForObject("""
				SELECT count(*) FROM po_events
				WHERE po_id = ? AND event_type = 'WHATSAPP_SENT'
				  AND created_at > now() - make_interval(secs => ?)
				""", Integer.class, poId, RATE_LIMIT_SECONDS);
		if (recent != null && recent > 0) {
			throw new ApplicationException(ErrorCode.PO_WHATSAPP_RATE_LIMITED, Map.of("purchaseOrderId", poId));
		}
	}

	private java.util.Optional<UUID> latestReadySheet(UUID poId) {
		List<UUID> ids = jdbc.queryForList("""
				SELECT id FROM documents
				WHERE po_id = ? AND kind = 'PURCHASE_ORDER_PDF' AND status = 'READY'
				ORDER BY version DESC LIMIT 1
				""", UUID.class, poId);
		return ids.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(ids.get(0));
	}

	private static String summarize(List<PurchaseOrderLineView> lines) {
		String names = lines.stream().limit(SUMMARY_ITEMS)
				.map(PurchaseOrderLineView::ingredientName)
				.collect(Collectors.joining(", "));
		int extra = lines.size() - SUMMARY_ITEMS;
		String suffix = extra > 0 ? " and " + extra + " more" : "";
		return lines.size() + " item(s): " + names + suffix;
	}
}
