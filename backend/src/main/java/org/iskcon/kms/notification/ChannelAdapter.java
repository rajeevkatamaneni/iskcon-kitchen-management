package org.iskcon.kms.notification;

import org.iskcon.kms.user.User.NotificationChannel;

/**
 * How a message actually leaves the building on one channel. Every channel — WhatsApp, SMS, email —
 * is one implementation, so the fallback cascade and the rest of the system depend on the interface,
 * not on any provider. Swapping the dev implementations for real Meta / SMS / email providers is a
 * change of these classes and nothing else.
 */
public interface ChannelAdapter {

	NotificationChannel channel();

	/**
	 * Sends the message to a single address (a phone for WhatsApp/SMS, an email for email).
	 *
	 * <p>The message carries both its rendered sentence and the template it came from, because
	 * WhatsApp can only send the latter — see {@link OutboundMessage}.
	 *
	 * @return the outcome — on success, the id the provider gave the message, which a delivery
	 *     webhook is later keyed on.
	 */
	SendResult send(String address, OutboundMessage message);
}
