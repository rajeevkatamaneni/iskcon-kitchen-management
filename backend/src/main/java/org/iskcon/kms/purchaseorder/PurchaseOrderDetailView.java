package org.iskcon.kms.purchaseorder;

import java.util.List;

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
		 * <p>Derived from {@code tenant_settings.whatsapp_last_sent_at} (V123), which is written in
		 * exactly one place — {@code WhatsAppChannelAdapter}, after Meta hands back a message id.
		 */
		boolean whatsappEverSent) {
}
