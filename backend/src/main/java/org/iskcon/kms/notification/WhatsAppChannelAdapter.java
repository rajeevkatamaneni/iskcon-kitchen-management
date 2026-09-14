package org.iskcon.kms.notification;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.iskcon.kms.document.DocumentService;
import org.iskcon.kms.document.DocumentView;
import org.iskcon.kms.user.User.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
	private final ObjectProvider<DocumentService> documents;
	private final String languageCode;

	/**
	 * @param documents where a purchase-order sheet is read from (T-200). A provider rather than the service,
	 *     because {@link DocumentService} reaches the donation receipts, which send notifications, which reach
	 *     this adapter: asking for it when a sheet is needed keeps that circle out of construction.
	 */
	public WhatsAppChannelAdapter(
			TenantWhatsAppSettingsService settings,
			MetaWhatsAppClient meta,
			ObjectProvider<DocumentService> documents,
			@Value("${kms.notifications.whatsapp.language:en}") String languageCode) {
		this.settings = settings;
		this.meta = meta;
		this.documents = documents;
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

		// T-200: a template with a document header is never sent without its document. The sheet is read
		// before the try, and a sheet that cannot be read is this channel failing with a plain reason, so the
		// cascade moves on exactly as it does for Meta refusing the message.
		Attachment attachment = null;
		if (NotificationTemplate.HEADER_DOCUMENT.equals(message.template().whatsappHeaderFormat())) {
			attachment = attachment(message);
			if (attachment.problem() != null) {
				log.warn("WhatsApp send of template {} not attempted: {}",
						message.template().whatsappTemplateName(), attachment.problem());
				return SendResult.failed(attachment.problem());
			}
		}

		String providerMessageId;
		// The try holds the Meta calls and nothing else, which it did not have to before T-136 added
		// a second statement after it. A stamp that threw inside this block would be caught as a
		// send failure — reported as FAILED for a message Meta has already accepted, and the cascade
		// would then send the same order again by SMS. The vendor would get it twice because a
		// column could not be written.
		try {
			if (attachment == null) {
				providerMessageId = meta.sendTemplate(
						identity.get().phoneNumberId(),
						identity.get().accessToken(),
						address,
						message.template().whatsappTemplateName(),
						languageCode,
						whatsappParameters(message));
			} else {
				// Uploaded to the temple's number first, then named by its media id in the header. Both are
				// Meta calls, so both are inside the try: a refused upload is a refused send.
				String mediaId = meta.uploadMedia(identity.get().phoneNumberId(), identity.get().accessToken(),
						attachment.content(), attachment.filename(), PDF);
				providerMessageId = meta.sendTemplate(
						identity.get().phoneNumberId(),
						identity.get().accessToken(),
						address,
						message.template().whatsappTemplateName(),
						languageCode,
						whatsappParameters(message),
						new MetaWhatsAppClient.HeaderDocument(mediaId, attachment.filename()));
			}

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

	private static final String PDF = "application/pdf";

	static final String NO_SHEET =
			"This purchase order has no sheet to attach, so it was not sent on WhatsApp.";

	static final String SHEET_NOT_READY =
			"The purchase order sheet was not ready yet, so it was not sent on WhatsApp.";

	static final String SHEET_UNREADABLE =
			"The purchase order sheet could not be read, so it was not sent on WhatsApp.";

	/**
	 * The document a header carries, or the reason there is none (T-200).
	 *
	 * @param content  the file's bytes, exactly as stored
	 * @param filename what the recipient's phone shows under it
	 * @param problem  a plain sentence when the document could not be had; null when it could
	 */
	record Attachment(byte[] content, String filename, String problem) {

		static Attachment failed(String problem) {
			return new Attachment(null, null, problem);
		}
	}

	/**
	 * The sheet named by the message's header parameter, read as it is stored.
	 *
	 * <p><strong>Which sheet is not decided here.</strong> {@code PurchaseOrderDeliveryService} chose it when
	 * the order was sent, the translated one if the order was translated, and put its id in the message. This
	 * sends that document's bytes and nothing else, so the vendor receives exactly the PDF a person at the
	 * temple can download from the order.
	 *
	 * <p>Only a READY sheet is sent. A sheet still being made when this runs is a failure with its own reason,
	 * never a message without the PDF, and never a message with some other sheet in its place.
	 */
	private Attachment attachment(OutboundMessage message) {
		String raw = message.headerParameter();
		if (raw == null || raw.isBlank()) {
			return Attachment.failed(NO_SHEET);
		}
		UUID documentId;
		try {
			documentId = UUID.fromString(raw.trim());
		} catch (IllegalArgumentException e) {
			return Attachment.failed(NO_SHEET);
		}
		DocumentService service = documents.getIfAvailable();
		if (service == null) {
			return Attachment.failed(SHEET_UNREADABLE);
		}
		DocumentView sheet;
		try {
			sheet = service.get(documentId);
		} catch (RuntimeException e) {
			return Attachment.failed(NO_SHEET);
		}
		if (!"READY".equals(sheet.status())) {
			return Attachment.failed(SHEET_NOT_READY);
		}
		try (InputStream in = service.openForDownload(documentId)) {
			byte[] content = in.readAllBytes();
			if (content.length == 0) {
				return Attachment.failed(SHEET_UNREADABLE);
			}
			Object poNumber = message.params() == null ? null : message.params().get("poNumber");
			String filename = MetaWhatsAppClient.safeFileName(
					(poNumber == null ? "purchase-order" : poNumber.toString()) + ".pdf");
			return new Attachment(content, filename, null);
		} catch (java.io.IOException | RuntimeException e) {
			log.warn("Could not read purchase order sheet {}: {}", documentId, e.toString());
			return Attachment.failed(SHEET_UNREADABLE);
		}
	}

	/** A line break, a tab, or two or more spaces: anything a single space should stand for. */
	private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

	/**
	 * The message's values in placeholder order, each on one line (T-180).
	 *
	 * <p>Meta refuses a template parameter that holds a line break, so a coordinator's broadcast typed
	 * over two lines could not go on WhatsApp at all and fell through to email. Each run of whitespace
	 * becomes one space.
	 *
	 * <p><strong>Why here, and for every parameter.</strong> This adapter is the one place a
	 * notification becomes a WhatsApp send, and the SMS and email adapters never pass through it, so
	 * they keep the line breaks the admin typed. It is every parameter rather than only the broadcast's
	 * {@code message}, because Meta's refusal is about any parameter, and a temple's announcement intro
	 * is typed text too. A value with no line break or double space comes out unchanged.
	 */
	static List<String> whatsappParameters(OutboundMessage message) {
		return message.orderedParameters().stream()
				.map(value -> WHITESPACE_RUN.matcher(value).replaceAll(" ").strip())
				.toList();
	}
}
