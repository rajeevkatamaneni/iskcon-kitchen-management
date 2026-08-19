package org.iskcon.kms.communication;

import java.time.Instant;
import java.util.UUID;

/** One communication as the temple's own screen shows it (E8-S2). */
public record CommunicationView(
		UUID id,
		CommunicationCategory category,
		CommunicationChannel channel,
		String subject,
		/** Already sanitised — it was cleaned on the way in, not on the way out. */
		String bodyHtml,
		String bodyText,
		/** The one line WhatsApp carries in place of the letter. */
		String whatsappSummary,
		CommunicationStatus status,
		/** How many it went to, recorded at send time so the answer does not drift. */
		Integer audienceCount,
		String publicToken,
		String author,
		Instant createdAt,
		Instant sentAt) {
}
