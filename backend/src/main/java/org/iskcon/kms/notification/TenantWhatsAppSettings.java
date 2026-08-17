package org.iskcon.kms.notification;

import java.time.Instant;

/**
 * A temple's WhatsApp connection as its administrator sees it (E1, E5).
 *
 * <p>Everything here is safe to show: two Meta ids that address a send, the callback URL Meta must
 * be told about, and the dates that say whether each half works. The access token, the app secret
 * and the verify token are not here and no endpoint returns the first two.
 *
 * @param connected     whether this temple can send a WhatsApp message at all
 * @param phoneNumberId Meta's id for the temple's business number
 * @param wabaId        the WhatsApp Business Account that owns the approved templates
 * @param displayNumber the number as Meta describes it, so an administrator can see they connected
 *                      the one they meant to. Null until the credentials have been checked once.
 * @param webhookUrl    the address Meta must be told to call
 * @param verifiedAt    when the credentials last reached Meta
 * @param webhookSeenAt when a correctly signed callback last arrived — the only proof the return
 *                      path works, and a different question from {@code verifiedAt}
 * @param templatesSubmittedAt when the message templates were last sent to Meta for approval
 */
public record TenantWhatsAppSettings(
		boolean connected,
		String phoneNumberId,
		String wabaId,
		String displayNumber,
		String webhookUrl,
		Instant verifiedAt,
		Instant webhookSeenAt,
		Instant templatesSubmittedAt) {

	/** A temple that has not connected WhatsApp. */
	public static TenantWhatsAppSettings none() {
		return new TenantWhatsAppSettings(false, null, null, null, null, null, null, null);
	}
}
