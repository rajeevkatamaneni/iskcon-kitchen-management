package org.iskcon.kms.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * A temple administrator asking for a test WhatsApp message to be sent to one phone (T-151).
 *
 * <p>The rule is the one every other phone number in the application is held to, copied exactly
 * rather than loosened: {@code GlobalExceptionHandler} recognises a malformed phone number by this
 * regexp and answers {@code KMS-400003} for it, so the same string here is what earns an
 * administrator "include the country code" instead of the general "check the highlighted fields".
 *
 * <p>Blank is a different refusal on purpose, as it is on the staff and vendor forms: a missing
 * number is not a malformed one.
 */
public record SendWhatsAppTestRequest(
		@NotBlank(message = "Enter the phone number to send the test message to.")
		@Pattern(
				regexp = "^\\+[1-9][0-9]{7,14}$",
				message = "Include the country code, for example +919876543210.")
		String phoneNumber) {
}
