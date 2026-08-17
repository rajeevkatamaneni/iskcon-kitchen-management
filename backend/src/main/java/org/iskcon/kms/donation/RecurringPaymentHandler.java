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

	/** Exactly the four this class acts on — what a temple ticks in its provider's dashboard. */
	@Override
	public java.util.Set<String> subscribedEventTypes() {
		return java.util.Set.of(
				"subscription.charged", "subscription.halted",
				"subscription.cancelled", "subscription.pending");
	}

	/**
	 * Broader than what we subscribe to, on purpose. A provider sends the whole subscription
	 * lifecycle once any of it is subscribed, and an event this class does nothing with should be
	 * absorbed here rather than left to the dead-letter queue as if something had gone wrong.
	 */
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
