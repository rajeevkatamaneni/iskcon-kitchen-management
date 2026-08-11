package org.iskcon.kms.donation;

import com.fasterxml.jackson.databind.JsonNode;
import org.iskcon.kms.payment.PaymentEvent;
import org.iskcon.kms.payment.PaymentEventHandler;
import org.springframework.stereotype.Component;

/**
 * Turns Razorpay subscription webhooks into recurring-donation state (E7-S3): a charged cycle becomes
 * a donation on the plan, a halted/cancelled subscription updates the plan's status, and a pending
 * (failed) cycle notifies the donor. Registered with the E7-S9 spine; idempotent per cycle.
 */
@Component
public class RecurringPaymentHandler implements PaymentEventHandler {

	private final RecurringDonationService service;

	public RecurringPaymentHandler(RecurringDonationService service) {
		this.service = service;
	}

	@Override
	public boolean handles(String eventType) {
		return eventType.startsWith("subscription.");
	}

	@Override
	public void handle(PaymentEvent event) {
		JsonNode payload = event.payload().path("payload");
		String subscriptionId = text(payload.path("subscription").path("entity"), "id");
		if (subscriptionId == null) {
			return;
		}
		switch (event.eventType()) {
			case "subscription.charged" ->
					service.recordCharge(subscriptionId, text(payload.path("payment").path("entity"), "id"),
							text(payload.path("payment").path("entity"), "method"));
			case "subscription.halted" -> {
				service.updateStatus(subscriptionId, "HALTED");
				service.notifyFailedCycle(subscriptionId);
			}
			case "subscription.cancelled" -> service.updateStatus(subscriptionId, "CANCELLED");
			case "subscription.pending" -> service.notifyFailedCycle(subscriptionId);
			default -> { /* other subscription lifecycle events: nothing to do */ }
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode v = node.get(field);
		return v == null || v.isNull() ? null : v.asText();
	}
}
