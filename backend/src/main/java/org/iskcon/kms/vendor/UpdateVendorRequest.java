package org.iskcon.kms.vendor;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Edit a vendor's details (E5-S1). Active state changes through its own endpoints.
 *
 * <p>The phone is optional here for the same reason it is optional on the way in (T-025), and it
 * matters twice over on this request: clearing the number of a vendor the temple now only ever
 * walks into is a legitimate edit, and a vendor that never had one must be editable at all. The
 * {@code @Pattern} stays, so a number that IS supplied is still a real E.164 one.
 */
public record UpdateVendorRequest(
		@NotBlank(message = "Enter the vendor's name.")
		@Size(max = 200, message = "That name is too long.")
		String name,
		@Size(max = 200, message = "That name is too long.") String contactPerson,
		@Pattern(regexp = "^\\+[1-9][0-9]{7,14}$",
				message = "Include the country code, for example +919876543210.") String phone,
		@Email(message = "That doesn't look like an email address.")
		@Size(max = 200, message = "That email address is too long.")
		String email,
		@Size(max = 500, message = "That address is too long.") String address,
		@Size(max = 30, message = "That GSTIN is too long.") String gstin,
		@Size(max = 10, message = "That language code is too long.") String preferredLanguage,
		@Size(max = 1000, message = "That note is too long.") String notes,
		/**
		 * When the agreement with this vendor runs out, or null if there is no such date. Recorded
		 * and warned about, never acted on — it does not deactivate anybody.
		 */
		LocalDate contractEndDate) {
}
