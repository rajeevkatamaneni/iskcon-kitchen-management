package org.iskcon.kms.vendor;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Add a vendor. The phone is the WhatsApp destination, so it must be a valid E.164 number. */
public record CreateVendorRequest(
		@NotBlank @Size(max = 200) String name,
		@Size(max = 200) String contactPerson,
		@NotBlank @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$",
				message = "Include the country code, for example +919876543210.") String phone,
		@Email @Size(max = 200) String email,
		@Size(max = 500) String address,
		@Size(max = 30) String gstin,
		@Size(max = 10) String preferredLanguage,
		@Size(max = 1000) String notes,
		/**
		 * When the agreement with this vendor runs out, or null if there is no such date. Recorded
		 * and warned about, never acted on — it does not deactivate anybody.
		 */
		LocalDate contractEndDate) {
}
