package org.iskcon.kms.communication;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Writing or re-writing a draft (E8-S2).
 *
 * <p>{@code bodyHtml} arrives as whatever the composer produced or the admin pasted, and is
 * sanitised before it is stored — so nothing unsafe is ever at rest and no later reader has to
 * remember to clean it.
 */
public record SaveCommunicationRequest(

		@NotNull(message = "Choose what kind of message this is.")
		CommunicationCategory category,

		@NotNull(message = "Choose how to send it.")
		CommunicationChannel channel,

		@NotBlank(message = "Give it a subject.")
		@Size(max = 200, message = "That subject is too long.")
		String subject,

		@Size(max = 200_000, message = "That letter is too long to send in one message.")
		String bodyHtml,

		/** WhatsApp's one line. Required for that channel, meaningless for email. */
		@Size(max = 300, message = "Keep the WhatsApp line short — it sits above the link.")
		String whatsappSummary) {
}
