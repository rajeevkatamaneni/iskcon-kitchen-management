package org.iskcon.kms.notification;

import java.util.Optional;
import org.iskcon.kms.user.User.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The WhatsApp channel, sending as whichever temple the message belongs to.
 *
 * <p>One adapter rather than a Meta one and a dev one, because which of those applies is a property
 * of the temple and not of the deployment. A temple that has connected its WhatsApp Business account
 * sends through Meta as itself; a temple that has not is logged and reported as a failure, which
 * drops the message to SMS and then email through the cascade that already exists. That is the
 * honest behaviour: a temple with no WhatsApp cannot send WhatsApp, and pretending otherwise would
 * mark messages delivered that nobody ever received.
 */
@Component
public class WhatsAppChannelAdapter implements ChannelAdapter {

	private static final Logger log = LoggerFactory.getLogger(WhatsAppChannelAdapter.class);

	private final TenantWhatsAppSettingsService settings;
	private final MetaWhatsAppClient meta;
	private final String languageCode;

	public WhatsAppChannelAdapter(
			TenantWhatsAppSettingsService settings,
			MetaWhatsAppClient meta,
			@Value("${kms.notifications.whatsapp.language:en}") String languageCode) {
		this.settings = settings;
		this.meta = meta;
		this.languageCode = languageCode;
	}

	@Override
	public NotificationChannel channel() {
		return NotificationChannel.WHATSAPP;
	}

	@Override
	public SendResult send(String address, OutboundMessage message) {
		Optional<TenantWhatsAppSettingsService.SendingIdentity> identity = settings.sendingIdentity();
		if (identity.isEmpty()) {
			// Not an error worth an incident: most temples will not have connected WhatsApp yet.
			return SendResult.failed("this temple has not connected WhatsApp");
		}

		try {
			String providerMessageId = meta.sendTemplate(
					identity.get().phoneNumberId(),
					identity.get().accessToken(),
					address,
					message.template().whatsappTemplateName(),
					languageCode,
					message.orderedParameters());
			return SendResult.sent(providerMessageId);

		} catch (RuntimeException e) {
			// Meta refusing one message — an unapproved template, a number outside the test list, a
			// rate limit — is a reason to try SMS, not a reason to stop. The cascade does that; this
			// only has to say why, loudly enough to find later.
			log.warn("WhatsApp send failed for template {}: {}",
					message.template().whatsappTemplateName(), e.toString());
			return SendResult.failed(e.getMessage());
		}
	}
}
