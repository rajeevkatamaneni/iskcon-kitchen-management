package org.iskcon.kms.vendor;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Edit a vendor's details (E5-S1). Active state changes through its own endpoints. */
public record UpdateVendorRequest(
		@NotBlank @Size(max = 200) String name,
		@Size(max = 200) String contactPerson,
		@NotBlank @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$",
				message = "Include the country code, for example +919876543210.") String phone,
		@Email @Size(max = 200) String email,
		@Size(max = 500) String address,
		@Size(max = 30) String gstin,
		@Size(max = 10) String preferredLanguage,
		@Size(max = 1000) String notes) {
}
