package org.iskcon.kms.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A temple administrator connecting their WhatsApp Business account (E1, E5).
 *
 * <p>{@code accessToken} and {@code appSecret} are optional for the same reason the payment key
 * secret is: neither is ever sent back to the screen, so correcting a mistyped phone number id must
 * not mean re-typing secrets nobody can see. Left blank, the stored ones are kept and re-proven;
 * supplied, they replace what is there.
 *
 * <p>{@code appId} is the temple's Meta App ID (T-200), not a secret and shown in full, so it behaves like
 * the two ids and not like the secrets: what is sent is what is stored. Blank clears it. Absent, as from a
 * caller that predates the box, keeps what is stored. Digits only, as Meta writes it, which is also what
 * stops a pasted token being saved into a box the screen shows in full.
 */
public record SaveWhatsAppSettingsRequest(
		@NotBlank(message = "Enter the phone number id from your WhatsApp Business account.")
		@Size(max = 64, message = "That id is too long.")
		String phoneNumberId,
		@NotBlank(message = "Enter the account id from your WhatsApp Business account.")
		@Size(max = 64, message = "That id is too long.")
		String wabaId,
		@Size(max = 500, message = "That access key is too long.") String accessToken,
		@Size(max = 200, message = "That secret is too long.") String appSecret,
		@Size(max = 32, message = "That App ID is too long.")
		@Pattern(regexp = "\\s*\\d*\\s*", message = "An App ID is digits only.")
		String appId) {
}
