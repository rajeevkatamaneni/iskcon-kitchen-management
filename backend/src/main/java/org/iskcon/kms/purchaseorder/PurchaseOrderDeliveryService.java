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
 *
 * <p>A vendor with no phone number at all cannot be sent to, and says so (KMS-400130, T-025) before
 * the draft is touched. That is not a failure of the vendor record: since V101 a vendor need not
 * have a number, because the shop the temple walks into and pays at the counter has nothing to send
 * to and needs nothing to send to. The order is downloaded and handed over instead.
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

	/**
	 * Sends (or resends) the PO to its vendor on WhatsApp; returns the notification's id.
	 *
	 * <p><strong>Every refusal happens before anything changes.</strong> The three questions that can
	 * stop a send — is the order closed, has the vendor a number to send to, and is this a repeat
	 * inside the rate window — are all answered above the one statement that mutates, the DRAFT
	 * transition. The order they are asked in is not arbitrary either: the order's own state first,
	 * because a cancelled order cannot be sent to anybody; then the destination; then the guard
	 * against sending the same thing twice.
	 *
	 * <p>That shape is the T-025 fix and not tidying. The phone check used to be no check at all: the
	 * number was read after the transition and passed straight to the notification service, which
	 * built a recipient label by concatenation ("Vendor null") and then refused a moment later with a
	 * generic VALIDATION_FAILED whose text said "no contact address". The transaction rolled the
	 * transition back, so nothing was corrupted — but the person got a meaningless 400 for the
	 * entirely sensible situation of ordering from a shop they walk into, and the order they were
	 * looking at had, for the length of that transaction, been moved to SENT. Asking first means
	 * KMS-400130 says what is wrong and what to do instead, and the draft is never touched.
	 */
	@Transactional
	public UUID sendViaWhatsApp(AuthenticatedUser actor, UUID poId) {
		PurchaseOrderDetailView po = purchaseOrders.get(poId);
		PoStatus status = po.order().status();
		// CLOSED sits with the other two terminal states (T-142, D-26). Somebody has ended this
		// order, its remainder is back on the shopping list and may already have been re-ordered
		// elsewhere; sending the sheet again would ask the vendor for goods the temple has stopped
		// expecting from them.
		if (status == PoStatus.RECEIVED || status == PoStatus.CANCELLED
				|| status == PoStatus.CLOSED) {
			throw new ApplicationException(ErrorCode.PO_NOT_SENDABLE, Map.of("purchaseOrderId", poId));
		}

		Map<String, Object> vendor = jdbc.queryForMap(
				"SELECT name, phone FROM vendors WHERE id = ?", po.order().vendorId());
		String vendorName = (String) vendor.get("name");
		String phone = (String) vendor.get("phone");
		if (phone == null) {
			// A vendor with no number is not a broken record — it is the hardware shop somebody walks
			// into, and `vendors.phone` is nullable for exactly that (V101, T-025). There is nowhere
			// to send, and the answer is to hand the sheet over instead, which is what KMS-400130
			// says. Note this is NOT `vendors.whatsapp_reachable`, which is a stored flag about a
			// number that exists and bounced; a phoneless vendor reads `true` on it like any other.
			throw new ApplicationException(ErrorCode.VENDOR_HAS_NO_WHATSAPP_NUMBER,
					Map.of("purchaseOrderId", poId, "vendorId", po.order().vendorId()));
		}

		guardRate(poId);

		if (status == PoStatus.DRAFT) {
			// Sending a draft transitions it to SENT (and generates its sheet) as part of delivering.
			purchaseOrders.send(actor, poId);
			po = purchaseOrders.get(poId);
		}

		Map<String, Object> params = new HashMap<>();
		params.put("poNumber", po.order().poNumber());
		params.put("vendor", vendorName);
		params.put("summary", summarize(po.lines()));
		// The three dates, as parameters of their own rather than smuggled into the summary text.
		//
		// This changes the shape of the Meta template: `po_delivery` now takes five parameters and
		// has to be re-registered and approved before a send will succeed. That is a deployment step,
		// not a code one, and it is safe to take now because the product is pre-beta — Rajeev,
		// 2026-09-05: "We are still in dev adn test. Not even beta. So a change like this harms no
		// one." Once a temple is live it would not be, and the two-message migration would be the
		// only honest way to do it.
		params.put("raised", WHEN.format(po.order().orderDate()));
		params.put("neededBy", po.order().neededBy() == null
				? "no fixed date" : WHEN.format(po.order().neededBy()));
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

	/**
	 * The first few things on the order, in words, for a message a vendor actually reads.
	 *
	 * <p>{@link PurchaseOrderLineView#subject()} and never {@code ingredientName()}. Since T-024 a
	 * line may name a description instead of a catalogue ingredient, which makes
	 * {@code ingredientName()} null — and {@link Collectors#joining} appends a null element as the
	 * four literal characters "null" with no warning from the compiler and no failure at runtime. The
	 * result was a WhatsApp message to a real supplier reading "3 item(s): Rice, null, Sugar".
	 * {@code subject()} exists precisely so this is one call and cannot be got wrong again.
	 */
	private static String summarize(List<PurchaseOrderLineView> lines) {
		String names = lines.stream().limit(SUMMARY_ITEMS)
				.map(PurchaseOrderLineView::subject)
				.collect(Collectors.joining(", "));
		int extra = lines.size() - SUMMARY_ITEMS;
		String suffix = extra > 0 ? " and " + extra + " more" : "";

		return lines.size() + " item(s): " + names + suffix;
	}

	/** Short and unambiguous in a message that may be read on a small screen. */
	private static final java.time.format.DateTimeFormatter WHEN =
			java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy");
}
