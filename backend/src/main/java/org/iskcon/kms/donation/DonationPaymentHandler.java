package org.iskcon.kms.donation;

import com.fasterxml.jackson.databind.JsonNode;
import org.iskcon.kms.payment.PaymentEvent;
import org.iskcon.kms.payment.PaymentEventHandler;
import org.springframework.stereotype.Component;

/**
 * Turns Razorpay payment webhooks into donation state (E7-S2): a captured payment completes the
 * PENDING donation its order belongs to; a failed one marks it failed. Registered with the E7-S9
 * webhook spine, which handles dedup, ordering, and dead-lettering; this handler is idempotent
 * because the service only advances a PENDING record.
 */
@Component
public class DonationPaymentHandler implements PaymentEventHandler {

	private final MonetaryDonationService donationService;

	public DonationPaymentHandler(MonetaryDonationService donationService) {
		this.donationService = donationService;
	}

	@Override
	public boolean handles(String eventType) {
		return "payment.captured".equals(eventType) || "payment.failed".equals(eventType);
	}

	@Override
	public void handle(PaymentEvent event) {
		JsonNode entity = event.payload().path("payload").path("payment").path("entity");
		String orderId = text(entity, "order_id");
		if (orderId == null) {
			return;
		}
		if ("payment.captured".equals(event.eventType())) {
			donationService.completePayment(orderId, text(entity, "id"), text(entity, "method"));
		} else {
			donationService.failPayment(orderId);
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode v = node.get(field);
		return v == null || v.isNull() ? null : v.asText();
	}
}
