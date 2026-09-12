package org.iskcon.kms.purchaseorder;

import java.util.List;
import org.iskcon.kms.vendor.OrderDeliveryScore;

/**
 * A purchase order with its lines and activity trail (E5-S3).
 *
 * @param whatsappEverSent whether a WhatsApp message from this temple has ever gone out
 *     successfully — which is what decides whether the order screen offers Send on WhatsApp at all
 *     (T-136). See the field's own note below.
 */
public record PurchaseOrderDetailView(
		PurchaseOrderView order,
		List<PurchaseOrderLineView> lines,
		List<PoEventView> events,

		/**
		 * Whether this temple's WhatsApp has ever actually sent something (T-136).
		 *
		 * <p>Rajeev's ruling, 2026-09-10: the Send on WhatsApp button is shown "only after a message
		 * has actually gone through it successfully", not merely configured — and where it does not
		 * apply it is not there at all, not disabled and not greyed. False here means the button is
		 * absent.
		 *
		 * <p><strong>A tenant-wide fact riding on an order, and that is deliberate.</strong> It is
		 * not about this order and says nothing about whether this order was ever sent — that is
		 * {@link PurchaseOrderView#sentAt()}. It is here because this is the payload the order screen
		 * already reads, under {@code MANAGE_PURCHASE_ORDERS}.
		 *
		 * <p>The alternative was for the screen to call {@code GET /api/v1/settings/whatsapp}, and
		 * that is the trap this avoids. That endpoint is behind {@code MANAGE_TEMPLE_SETTINGS},
		 * which the person raising a purchase order need not hold: the button would then disappear
		 * for a Kitchen Manager whose WhatsApp works perfectly, for a reason that is about
		 * permissions and nothing to do with WhatsApp. A permission that was right for an endpoint's
		 * original job and wrong the moment something else read it is a known defect class here.
		 *
		 * <p>Derived from {@code tenant_settings.whatsapp_last_sent_at} (V123), which is written by one
		 * method, {@code TenantWhatsAppSettingsService.markMessageSent}, and only after Meta hands back
		 * a message id. That method has two callers: {@code WhatsAppChannelAdapter}, for a real
		 * notification, and the settings screen's Test button (T-151), which sends a real message too.
		 */
		boolean whatsappEverSent,

		/**
		 * What this order scored on delivery, shown and never editable (T-142, D-26).
		 *
		 * <p>The figure the vendor scorecard will report for this order, read from the same
		 * arithmetic rather than worked out again. It is on this payload so the person closing a
		 * part-delivered order decides against a fact: 300 kg of 500 inside the window is 60%, and
		 * that number is in front of them while they choose what the shortfall meant.
		 *
		 * <p><strong>Shown, and that is the whole of it.</strong> Rajeev asked for a control beside
		 * it that let an admin adjust the number, and then ruled against his own proposal — "Let us
		 * not let the admin adjust the score. Just show it to them." There is no endpoint, no
		 * column and no request field anywhere in this application that moves it. What an admin may
		 * say is {@link PurchaseOrderView#closeOutcome()}: a name, never a number.
		 *
		 * <p>Present on every order, not only the closeable ones. It costs one query on a screen
		 * that already runs three, and an order's delivery record is worth reading wherever
		 * somebody has the order open.
		 */
		OrderDeliveryScore deliveryScore) {
}
