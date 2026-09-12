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

		String providerMessageId;
		// The try holds the Meta call and nothing else, which it did not have to before T-136 added
		// a second statement after it. A stamp that threw inside this block would be caught as a
		// send failure — reported as FAILED for a message Meta has already accepted, and the cascade
		// would then send the same order again by SMS. The vendor would get it twice because a
		// column could not be written.
		try {
			providerMessageId = meta.sendTemplate(
					identity.get().phoneNumberId(),
					identity.get().accessToken(),
					address,
					message.template().whatsappTemplateName(),
					languageCode,
					message.orderedParameters());

		} catch (RuntimeException e) {
			// Meta refusing one message — an unapproved template, a number outside the test list, a
			// rate limit — is a reason to try SMS, not a reason to stop. The cascade does that; this
			// only has to say why, loudly enough to find later.
			log.warn("WhatsApp send failed for template {}: {}",
					message.template().whatsappTemplateName(), e.toString());
			return SendResult.failed(e.getMessage());
		}

		// Where "this temple's WhatsApp actually works" becomes a fact for a real notification (T-136,
		// V123). The column is written only by TenantWhatsAppSettingsService.markMessageSent, and that
		// method has two callers, each on the far side of a Meta call that put a message in front of a
		// real person: this one, and the settings screen's Test button (T-151, sendTestMessage on the
		// same service), which since T-151 sends a real message rather than only checking the number.
		//
		// Rajeev, 2026-09-10, on the Send on WhatsApp button on the purchase-order screen: it is
		// shown "only after a message has actually gone through it successfully", not merely
		// configured. Everything else this application stores about WhatsApp says configured —
		// whatsapp_verified_at is MetaWhatsAppClient.verifyNumber, a GET that deliberately sends
		// nothing; whatsapp_templates_submitted_at is us asking Meta for approval, not getting it;
		// whatsapp_webhook_seen_at is Meta calling us. Gating a button on any of those is the defect,
		// not the fix.
		//
		// After the send and only on the success path. The failures this distinguishes — an
		// unapproved template, a number outside Meta's test list, a spent messaging tier — all throw
		// out of sendTemplate with credentials that are perfectly valid, which is exactly the case
		// where verifying the number would have said yes.
		settings.markMessageSent();
		return SendResult.sent(providerMessageId);
	}
}
