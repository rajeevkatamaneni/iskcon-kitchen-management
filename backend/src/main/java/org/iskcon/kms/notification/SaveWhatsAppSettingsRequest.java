package org.iskcon.kms.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A temple administrator connecting their WhatsApp Business account (E1, E5).
 *
 * <p>{@code accessToken} and {@code appSecret} are optional for the same reason the payment key
 * secret is: neither is ever sent back to the screen, so correcting a mistyped phone number id must
 * not mean re-typing secrets nobody can see. Left blank, the stored ones are kept and re-proven;
 * supplied, they replace what is there.
 */
public record SaveWhatsAppSettingsRequest(
		@NotBlank @Size(max = 64) String phoneNumberId,
		@NotBlank @Size(max = 64) String wabaId,
		@Size(max = 500) String accessToken,
		@Size(max = 200) String appSecret) {
}
