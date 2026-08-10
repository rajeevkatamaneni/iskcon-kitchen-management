package org.iskcon.kms.notification;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.iskcon.kms.user.User.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A stand-in channel that logs the message instead of sending it, for use until the real WhatsApp,
 * SMS, and email providers are procured and wired. It exists so the whole notification machine — the
 * fallback cascade, delivery records, the webhook — can be built and tested end to end now, and a
 * real provider becomes a drop-in replacement for the one class of its channel.
 *
 * <p>It can be told to fail on named channels via {@code kms.notifications.dev.fail-channels}, which
 * is how "a forced WhatsApp failure falls back to SMS" is exercised without a real provider.
 */
public abstract class LoggingChannelAdapter implements ChannelAdapter {

	private static final Logger log = LoggerFactory.getLogger(LoggingChannelAdapter.class);

	private final Set<NotificationChannel> forcedFailures;

	protected LoggingChannelAdapter(String failChannelsCsv) {
		this.forcedFailures = parse(failChannelsCsv);
	}

	@Override
	public SendResult send(String address, RenderedMessage message) {
		if (forcedFailures.contains(channel())) {
			log.warn("[dev] {} send to {} forced to fail", channel(), address);
			return SendResult.failed("forced failure (dev)");
		}

		String providerMessageId = "dev-" + channel().name().toLowerCase() + "-" + UUID.randomUUID();
		log.info("[dev] {} → {} | {} — {} (id {})",
				channel(), address, message.subject(), message.body(), providerMessageId);
		return SendResult.sent(providerMessageId);
	}

	private static Set<NotificationChannel> parse(String csv) {
		if (csv == null || csv.isBlank()) {
			return Set.of();
		}
		return Arrays.stream(csv.split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.map(s -> NotificationChannel.valueOf(s.toUpperCase()))
				.collect(Collectors.toUnmodifiableSet());
	}
}
