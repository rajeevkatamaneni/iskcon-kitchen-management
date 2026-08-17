package org.iskcon.kms.payment;

import java.util.List;

/**
 * A group of webhook events, and what a temple gets by subscribing to them (E7).
 *
 * <p>One flat list was wrong. A Razorpay dashboard offers {@code subscription.*} events only once
 * Subscriptions has been activated on that account, so a screen listing them beside the payment
 * events tells most administrators to tick boxes that are not there — which reads as the screen
 * being broken, or as them having done something wrong. Neither is true.
 *
 * <p>So the events arrive grouped by what they are for, and each group says whether a temple needs
 * it. The essential group is what every temple must have or donations are taken and never recorded.
 * The rest belong to features a temple may not use and a provider may not have switched on.
 *
 * @param purpose   what these events are for, in a temple administrator's words
 * @param essential whether a temple that skips this group has a broken donation flow
 * @param events    the event types themselves, exactly as the provider names them
 */
public record WebhookSubscription(String purpose, boolean essential, List<String> events) {
}
