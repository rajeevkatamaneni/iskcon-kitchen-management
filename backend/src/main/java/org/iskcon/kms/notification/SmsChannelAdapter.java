package org.iskcon.kms.notification;

import org.iskcon.kms.user.User.NotificationChannel;
import org.springframework.stereotype.Component;

/**
 * SMS, which this application cannot send.
 *
 * <p>No provider has been procured and none is wired. What stood here before logged the message and
 * reported success, so every notification that reached SMS was recorded as SENT having gone
 * nowhere — a fiction written into the notifications table, and one nobody could see because a
 * delivery record that says SENT is exactly what you would check.
 *
 * <p>It says no now. The cascade carries the message on to email, and a message that genuinely could
 * not be sent is recorded as failed, which is the least a delivery record owes anyone. This class
 * exists rather than the channel being deleted because volunteers have SMS as a stored preference
 * and vendors are reached by phone; when a provider is chosen, only this file changes.
 */
@Component
public class SmsChannelAdapter implements ChannelAdapter {

	@Override
	public NotificationChannel channel() {
		return NotificationChannel.SMS;
	}

	@Override
	public SendResult send(String address, OutboundMessage message) {
		return SendResult.failed("no SMS provider is configured");
	}
}
