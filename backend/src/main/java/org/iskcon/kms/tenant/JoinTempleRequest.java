package org.iskcon.kms.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What a devotee tells us when they join a temple (E1-S16).
 *
 * <p>Firebase proves an email or a phone and nothing else — not a name, and not the other contact.
 * A temple needs both: a name to recognise someone by on a shift, and a number to reach them on. So
 * they are asked for here rather than guessed at, whichever way the person signed in.
 */
public record JoinTempleRequest(

		@NotBlank(message = "Enter your first name.")
		@Size(max = 100, message = "That name is too long.")
		String firstName,

		@NotBlank(message = "Enter your last name.")
		@Size(max = 100, message = "That name is too long.")
		String lastName,

		@NotBlank(message = "Enter a phone number.")
		@Pattern(regexp = "^\\+[1-9][0-9]{7,14}$",
				message = "Enter the number with its country code, like +919876543210.")
		String phone,

		/** Optional: what the temple can reach them at by email, where Firebase did not verify one. */
		@Size(max = 320, message = "That email address is too long.")
		String email) {

	/** How the temple's people list will show them. */
	public String fullName() {
		return (firstName.trim() + " " + lastName.trim()).trim();
	}
}
