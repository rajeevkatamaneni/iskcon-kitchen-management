package org.iskcon.kms.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A temple administrator storing their gateway (E7).
 *
 * <p>{@code keySecret} is optional on purpose: once saved it is never sent back to the screen, so an
 * administrator correcting a typo in the key id would otherwise have to re-type a secret they cannot
 * see. Left blank, the stored one is kept and re-proven; supplied, it replaces it.
 */
public record SavePaymentSettingsRequest(
		@NotBlank(message = "Choose a payment provider.")
		@Size(max = 40, message = "That name is too long.")
		String provider,
		@NotBlank(message = "Enter the key id from your payment provider.")
		@Size(max = 200, message = "That key id is too long.")
		String keyId,
		@Size(max = 200, message = "That secret is too long.") String keySecret) {
}
