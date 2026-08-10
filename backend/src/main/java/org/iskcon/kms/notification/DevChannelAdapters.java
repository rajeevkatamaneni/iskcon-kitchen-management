package org.iskcon.kms.notification;

import org.iskcon.kms.user.User.NotificationChannel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The three dev channel adapters, one per channel. Grouped in one file because each is a two-line
 * declaration over {@link LoggingChannelAdapter}; each will be replaced independently by a real
 * provider adapter (Meta WhatsApp Cloud API, an SMS provider, a transactional email provider) that
 * implements {@link ChannelAdapter} for the same channel.
 */
public final class DevChannelAdapters {

	private DevChannelAdapters() {
	}

	@Component
	public static class WhatsApp extends LoggingChannelAdapter {
		public WhatsApp(@Value("${kms.notifications.dev.fail-channels:}") String failChannels) {
			super(failChannels);
		}

		@Override
		public NotificationChannel channel() {
			return NotificationChannel.WHATSAPP;
		}
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
