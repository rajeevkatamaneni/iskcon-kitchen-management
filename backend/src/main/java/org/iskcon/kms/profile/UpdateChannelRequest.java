package org.iskcon.kms.profile;

import jakarta.validation.constraints.NotBlank;

/**
 * A request to change one's preferred notification channel. Carried as a string and validated in
 * the service so an unrecognised value is ordinary validation, not a 500.
 */
public record UpdateChannelRequest(
		@NotBlank(message = "A preferred channel is required.") String preferredChannel) {
}
