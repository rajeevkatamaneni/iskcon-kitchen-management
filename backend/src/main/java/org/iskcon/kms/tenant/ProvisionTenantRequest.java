package org.iskcon.kms.tenant;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Everything needed to bring a temple onto the platform, including its first administrator.
 *
 * <p>Deliberately one request rather than two steps. A tenant with no administrator is a dead
 * record nobody can reach — provisioning either produces a temple someone can sign into, or it
 * produces nothing.
 *
 * <p>Validation messages here are user-facing and follow the same rules as error codes: plain
 * language, no jargon, and they say what to do rather than what is wrong.
 */
public record ProvisionTenantRequest(
		@NotBlank(message = "Enter the temple's name.")
		@Size(max = 200, message = "That name is too long.")
		String name,

		@NotBlank(message = "Enter a web address for this temple.")
		@Pattern(
				regexp = "^[a-z0-9]([a-z0-9-]*[a-z0-9])?$",
				message = "Use only lowercase letters, numbers and hyphens, for example radha-govinda.")
		@Size(max = 60, message = "That web address is too long.")
		String slug,

		@Size(max = 500, message = "That address is too long.")
		String address,

		@NotNull(message = "Enter the temple's latitude.")
		@DecimalMin(value = "-90", message = "Latitude must be between -90 and 90.")
		@DecimalMax(value = "90", message = "Latitude must be between -90 and 90.")
		BigDecimal latitude,

		@NotNull(message = "Enter the temple's longitude.")
		@DecimalMin(value = "-180", message = "Longitude must be between -180 and 180.")
		@DecimalMax(value = "180", message = "Longitude must be between -180 and 180.")
		BigDecimal longitude,

		@NotBlank(message = "Choose the temple's timezone.")
		String timezone,

		@NotBlank(message = "Choose a currency.")
		@Pattern(regexp = "^[A-Z]{3}$", message = "Use a three-letter currency code, for example INR.")
		String currency,

		@NotNull(message = "Say whether this temple is approved for 80G receipts.")
		Boolean is80gApproved,

		@NotBlank(message = "Enter the administrator's full name.")
		@Size(max = 200, message = "That name is too long.")
		String adminName,

		@NotBlank(message = "Enter the administrator's email address.")
		@Email(message = "That doesn't look like an email address.")
		String adminEmail,

		@NotBlank(message = "Enter the administrator's phone number.")
		@Pattern(
				regexp = "^\\+[1-9][0-9]{7,14}$",
				message = "Include the country code, for example +919876543210.")
		String adminPhone) {
}
