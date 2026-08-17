package org.iskcon.kms.notification;

import org.iskcon.kms.user.User.NotificationChannel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The dev channel adapters for the channels that still have no real provider. Each is a two-line
 * declaration over {@link LoggingChannelAdapter}, and each will be replaced independently by an SMS
 * and a transactional email provider implementing {@link ChannelAdapter} for the same channel.
 *
 * <p>WhatsApp has left this file: it is real now, and which temple can send is a property of the
 * temple rather than of the deployment, so {@link WhatsAppChannelAdapter} owns both cases. A temple
 * that has connected nothing fails that channel and drops to SMS, which is what a dev adapter was
 * only ever pretending to do.
 */
public final class DevChannelAdapters {

	private DevChannelAdapters() {
	}

	@Component
	public static class Sms extends LoggingChannelAdapter {
		public Sms(@Value("${kms.notifications.dev.fail-channels:}") String failChannels) {
			super(failChannels);
		}

		@Override
		public NotificationChannel channel() {
			return NotificationChannel.SMS;
		}
	}

	@Component
	public static class Email extends LoggingChannelAdapter {
		public Email(@Value("${kms.notifications.dev.fail-channels:}") String failChannels) {
			super(failChannels);
		}

		@Override
		public NotificationChannel channel() {
			return NotificationChannel.EMAIL;
		}
	}
}
