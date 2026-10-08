package org.iskcon.kms.profile;

import jakarta.validation.constraints.NotBlank;

/**
 * A request to change one's own language (E10-LANG). Carried as a string and checked against
 * {@code translation/Languages} in the service, so an unknown code is ordinary validation, not a 500.
 */
public record UpdateLanguageRequest(
		@NotBlank(message = "Choose a language.") String preferredLanguage) {
}
