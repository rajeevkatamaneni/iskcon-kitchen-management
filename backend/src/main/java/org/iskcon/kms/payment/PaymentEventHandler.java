package org.iskcon.kms.payment;

import java.util.Set;

/**
 * A handler for one or more payment event types (E7-S9). The donation stories register these as
 * beans — one for captured payments (E7-S2/S6), one for subscription cycles (E7-S3) — and the
 * webhook service dispatches each stored event to whichever handlers claim its type. A handler must
 * be idempotent: the same event may be dispatched again on replay of a dead letter.
 */
public interface PaymentEventHandler {

	/**
	 * The events a provider must be asked to send for this handler to have anything to do.
	 *
	 * <p>Deliberately a different question from {@link #handles(String)}. What we accept can be
	 * broader than what we ask for — the recurring handler takes any {@code subscription.*} event and
	 * quietly ignores the lifecycle ones it has no use for — but a subscription list must be exact,
	 * because it is typed into a provider's dashboard or sent to its API, and neither understands
	 * "everything starting with". Every screen and every registration reads this one list, so the set
	 * a temple subscribes to cannot drift from the set this application acts on.
	 */
	Set<String> subscribedEventTypes();

	/** Whether this handler claims an event that has arrived. */
	default boolean handles(String eventType) {
		return subscribedEventTypes().contains(eventType);
	}

	/** Processes the event. Throwing parks it as a dead letter for replay after a fix. */
	void handle(PaymentEvent event);
}
