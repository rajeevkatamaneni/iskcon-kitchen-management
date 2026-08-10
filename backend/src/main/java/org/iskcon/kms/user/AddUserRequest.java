package org.iskcon.kms.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A request to add a person to the temple. Email and phone are both required (E1-S8): a person is
 * given a preferred channel, and we cannot offer a channel we have no address for. The role is a
 * string, validated in the service against the fixed set — {@code SUPER_ADMIN} is refused there.
 */
public record AddUserRequest(

		@NotBlank(message = "Enter the person's full name.")
		@Size(max = 200, message = "That name is too long.")
		String fullName,

		@NotBlank(message = "Enter an email address.")
		@Email(message = "That doesn't look like an email address.")
		String email,

		@NotBlank(message = "Enter a phone number.")
		@Pattern(
				regexp = "^\\+[1-9][0-9]{7,14}$",
				message = "Include the country code, for example +919876543210.")
		String phone,

		@NotBlank(message = "Choose a role.")
		String role,

		/** WhatsApp, SMS, or email. Optional — defaults to WhatsApp, per the India-first default. */
		String preferredChannel) {
}
