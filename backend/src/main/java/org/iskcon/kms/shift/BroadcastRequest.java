package org.iskcon.kms.shift;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A one-off update to a shift's volunteers (E6-S7). The message is length-capped to what the WhatsApp
 * template allows; {@code includeWaitlist} adds the waitlist to the signed-up recipients (default off).
 */
public record BroadcastRequest(
		@NotBlank @Size(max = 1000) String message,
		boolean includeWaitlist) {
}
