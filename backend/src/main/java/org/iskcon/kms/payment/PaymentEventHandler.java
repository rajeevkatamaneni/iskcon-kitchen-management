package org.iskcon.kms.payment;

/**
 * A handler for one or more payment event types (E7-S9). The donation stories register these as
 * beans — one for captured payments (E7-S2/S6), one for subscription cycles (E7-S3) — and the
 * webhook service dispatches each stored event to whichever handlers claim its type. A handler must
 * be idempotent: the same event may be dispatched again on replay of a dead letter.
 */
public interface PaymentEventHandler {

	boolean handles(String eventType);

	/** Processes the event. Throwing parks it as a dead letter for replay after a fix. */
	void handle(PaymentEvent event);
}
